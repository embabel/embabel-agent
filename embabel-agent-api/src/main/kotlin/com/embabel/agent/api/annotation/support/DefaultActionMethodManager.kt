/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.AwaitableResponseException
import com.embabel.agent.api.annotation.RequireNameMatch
import com.embabel.agent.api.annotation.SpecialReturnException
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.support.MultiTransformationAction
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Action
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DomainType
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.core.support.BlackboardWorldState
import com.embabel.common.core.types.ZeroToOne
import com.embabel.plan.CostComputation
import com.embabel.plan.WorldState
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.KotlinDetector
import org.springframework.stereotype.Component
import org.springframework.util.ReflectionUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.kotlinFunction

/**
 * Handles creation and invocation of Actions from annotated methods.
 * Implementation that creates dummy instances of domain objects to discover tools,
 * before re-reading the tool callbacks from the actual domain object instances at invocation time.
 */
@Component
internal class DefaultActionMethodManager(
    val nameGenerator: MethodDefinedOperationNameGenerator = MethodDefinedOperationNameGenerator(),
    val actionQosProvider: ActionQosProvider = DefaultActionQosProvider(),
    @Autowired(required = false) contextProvider: ContextProvider? = null,
    override val argumentResolvers: List<ActionMethodArgumentResolver> = buildArgumentResolvers(contextProvider),
) : ActionMethodManager {

    companion object {
        /**
         * Build the list of argument resolvers, optionally including
         * [ProvidedArgumentResolver] if a [ContextProvider] is available.
         */
        internal fun buildArgumentResolvers(
            contextProvider: ContextProvider?,
        ): List<ActionMethodArgumentResolver> {
            val resolvers = mutableListOf<ActionMethodArgumentResolver>(
                ProcessContextArgumentResolver(),
                OperationContextArgumentResolver(),
                AiArgumentResolver(),
            )
            // Add ProvidedArgumentResolver before BlackboardArgumentResolver
            // so @Provided takes precedence over blackboard lookup
            if (contextProvider != null) {
                resolvers += ProvidedArgumentResolver(contextProvider)
            }
            // BlackboardArgumentResolver should be last as a fallback
            resolvers += BlackboardArgumentResolver()
            return resolvers
        }
    }

    private val logger = LoggerFactory.getLogger(DefaultActionMethodManager::class.java)

    @Suppress("UNCHECKED_CAST")
    override fun createAction(
        method: Method,
        instance: Any,
        toolsOnInstance: List<Tool>,
        costMethods: Map<String, CostMethodInfo>,
    ): Action {
        requireNonAmbiguousParameters(method)
        val actionAnnotation = method.getAnnotation(com.embabel.agent.api.annotation.Action::class.java)
        val inputClasses = method.parameters
            .map { it.type }
        val inputs = resolveInputBindings(method)

        require(method.returnType != null) { "Action method ${method.name} must have a return type" }

        // Create cost computation - either from @Cost method or static value
        val costComputation = resolveCostComputation(
            methodName = actionAnnotation.costMethod,
            staticValue = actionAnnotation.cost,
            costMethods = costMethods,
            instance = instance,
        )

        // Create value computation - either from @Cost method or static value
        val valueComputation = resolveCostComputation(
            methodName = actionAnnotation.valueMethod,
            staticValue = actionAnnotation.value,
            costMethods = costMethods,
            instance = instance,
        )

        return MultiTransformationAction(
            name = nameGenerator.generateName(instance, method.name),
            description = actionAnnotation.description.ifBlank { method.name },
            cost = costComputation,
            value = valueComputation,
            inputs = inputs.toSet(),
            canRerun = actionAnnotation.canRerun,
            clearBlackboard = computeClearBlackboard(method, actionAnnotation),
            pre = actionAnnotation.pre.toList() + computeTriggerPreconditions(method),
            post = actionAnnotation.post.toList(),
            inputClasses = inputClasses,
            outputClass = method.returnType,
            outputVarName = actionAnnotation.outputBinding,
            toolGroups = computeToolGroups(actionAnnotation),
            qos = actionQosProvider.provideActionQos(method, instance),
        ) { context ->
            invokeActionMethod(
                method = method,
                instance = instance,
                actionContext = context,
            )
        }
    }

    /**
     * Create a CostComputation from a @Cost method reference or fall back to a static value.
     */
    private fun resolveCostComputation(
        methodName: String,
        staticValue: ZeroToOne,
        costMethods: Map<String, CostMethodInfo>,
        instance: Any,
    ): CostComputation {
        if (methodName.isBlank()) {
            return { staticValue }
        }
        val costMethodInfo = costMethods[methodName]
            ?: costMethods[nameGenerator.generateName(instance, methodName)]
        if (costMethodInfo == null) {
            logger.warn("@Cost method '{}' not found, falling back to static value {}", methodName, staticValue)
            return { staticValue }
        }
        return { worldState ->
            invokeCostMethod(costMethodInfo.method, costMethodInfo.instance, worldState)
        }
    }

    /**
     * Invoke a @Cost method with nullable domain object parameters.
     * Infrastructure parameters (OperationContext) are not supported at planning time.
     * Domain object parameters are resolved from the blackboard if available, otherwise null.
     */
    private fun invokeCostMethod(
        method: Method,
        instance: Any,
        worldState: WorldState,
    ): ZeroToOne {
        val blackboard = (worldState as? BlackboardWorldState)?.blackboard
        val args = arrayOfNulls<Any?>(method.parameters.size)

        for (i in method.parameters.indices) {
            val parameter = method.parameters[i]
            when {
                OperationContext::class.java.isAssignableFrom(parameter.type) -> {
                    logger.warn(
                        "@Cost method {}.{} has OperationContext parameter which is not available at planning time",
                        instance.javaClass.name,
                        method.name,
                    )
                    args[i] = null
                }

                Blackboard::class.java.isAssignableFrom(parameter.type) -> {
                    args[i] = blackboard
                }

                else -> {
                    // Domain object parameter - resolve from blackboard or pass null
                    if (blackboard != null) {
                        val requireNameMatch = parameter.getAnnotation(RequireNameMatch::class.java)
                        val variable = getBindingParameterName(parameter.name, requireNameMatch)
                            ?: IoBinding.DEFAULT_BINDING
                        args[i] = blackboard.getValue(
                            variable = variable,
                            type = parameter.type.name,
                            dataDictionary = EmptyDataDictionary,
                        )
                    } else {
                        args[i] = null
                    }
                }
            }
        }

        return try {
            method.trySetAccessible()
            val result = ReflectionUtils.invokeMethod(method, instance, *args)
            when (result) {
                is Double -> result
                is Number -> result.toDouble()
                else -> {
                    logger.warn(
                        "@Cost method {}.{} returned non-numeric value: {}",
                        instance.javaClass.name,
                        method.name,
                        result,
                    )
                    0.0
                }
            }
        } catch (t: Throwable) {
            logger.warn("Error invoking @Cost method {}.{}: {}", instance.javaClass.name, method.name, t.message)
            0.0
        }
    }

    private fun resolveInputBindings(
        javaMethod: Method,
    ): Set<IoBinding> {
        val result = mutableSetOf<IoBinding>()
        val kotlinFunction = if (KotlinDetector.isKotlinReflectPresent()) javaMethod.kotlinFunction else null
        for (i in javaMethod.parameters.indices) {
            val javaParameter = javaMethod.parameters[i]
            val kotlinParameter = kotlinFunction?.valueParameters?.getOrNull(i)
            for (argumentResolver in argumentResolvers) {
                if (argumentResolver.supportsParameter(javaParameter, kotlinParameter, null)) {
                    result += argumentResolver.resolveInputBinding(javaParameter, kotlinParameter)
                    break
                }
            }
        }
        return result
    }

    override fun <O> invokeActionMethod(
        method: Method,
        instance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        logger.debug("Invoking action method {} with payload {}", method.name, actionContext.input)
        val result = if (KotlinDetector.isKotlinReflectPresent()) {
            val kFunction = method.kotlinFunction
            if (kFunction != null) invokeActionMethodKotlinReflect(method, kFunction, instance, actionContext)
            else invokeActionMethodJavaReflect(method, instance, actionContext)
        } else {
            invokeActionMethodJavaReflect(method, instance, actionContext)
        }
        logger.debug(
            "Result of invoking action method {} was {}: payload {}",
            method.name,
            result,
            actionContext.input
        )
        return result
    }

    private fun <O> invokeActionMethodKotlinReflect(
        method: Method,
        kFunction: KFunction<*>,
        instance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        val args = arrayOfNulls<Any?>(method.parameters.size + 1)
        args[0] = instance
        for (i in method.parameters.indices) {
            val javaParameter = method.parameters[i]
            val kotlinParameter = kFunction.valueParameters.getOrNull(i)
            val classifier = kotlinParameter?.type?.classifier
            if (classifier is KClass<*>) {
                for (argumentResolver in argumentResolvers) {
                    if (argumentResolver.supportsParameter(javaParameter, kotlinParameter, actionContext)) {
                        val arg = argumentResolver.resolveArgument(javaParameter, kotlinParameter, actionContext)
                        if (arg == null) {
                            val isNullable = kotlinParameter.isOptional || kotlinParameter.type.isMarkedNullable
                            if (!isNullable) {
                                error("Action ${actionContext.action.name}: Internal error. No value found in blackboard for non-nullable parameter ${kotlinParameter.name}:${classifier.java.name}")
                            }
                        }
                        args[i + 1] = arg
                    }
                }
            }
        }

        val result = try {
            try {
                kFunction.isAccessible = true
                kFunction.call(*args)
            } catch (ite: InvocationTargetException) {
                ReflectionUtils.handleInvocationTargetException(ite)
            }
        } catch (sre: SpecialReturnException) {
            handleSpecial(sre, actionContext)
        } catch (awe: AwaitableResponseException) {
            handleAwaitableResponseException(instance.javaClass.name, kFunction.name, awe)
        } catch (t: Throwable) {
            handleThrowable(instance.javaClass.name, kFunction.name, t)
        }
        return result as O
    }

    private fun <O> invokeActionMethodJavaReflect(
        method: Method,
        instance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        val args = arrayOfNulls<Any?>(method.parameters.size)
        for (i in method.parameters.indices) {
            val parameter = method.parameters[i]
            for (argumentResolver in argumentResolvers) {
                if (argumentResolver.supportsParameter(parameter, null, actionContext)) {
                    val arg = argumentResolver.resolveArgument(parameter, null, actionContext)
                    args[i] = arg
                }
            }
        }

        val result = try {
            method.trySetAccessible()
            ReflectionUtils.invokeMethod(method, instance, *args)
        } catch (awe: AwaitableResponseException) {
            handleAwaitableResponseException(instance.javaClass.name, method.name, awe)
        } catch (t: Throwable) {
            handleThrowable(instance.javaClass.name, method.name, t)
        }
        return result as O
    }

    private fun handleAwaitableResponseException(
        instanceName: String,
        methodName: String,
        awe: AwaitableResponseException,
    ) {
        // This is not a failure, but will drive transition to a wait state
        logger.info(
            "Action method {}.{} entering wait state: {}",
            instanceName,
            methodName,
            awe.message,
        )
        throw awe
    }

    private fun handleSpecial(
        sre: SpecialReturnException,
        actionContext: ActionContext,
    ): Any = sre.handle(actionContext)

    private fun handleThrowable(
        instanceName: String,
        methodName: String,
        t: Throwable,
    ) {
        // ReplanRequestedException is a control flow signal, not an error
        // Throw it but don't log it
        if (t is ReplanRequestedException) {
            throw t
        }
        logger.warn(
            "Error invoking action method {}.{}: {}",
            instanceName,
            methodName,
            t.message,
        )
        throw t
    }

}

/**
 * Empty DataDictionary for use when resolving @Cost method parameters
 * at planning time without access to full agent metadata.
 */
private object EmptyDataDictionary : DataDictionary {
    override val name: String = "EmptyDataDictionary"
    override val domainTypes: Collection<DomainType> = emptyList()
}
