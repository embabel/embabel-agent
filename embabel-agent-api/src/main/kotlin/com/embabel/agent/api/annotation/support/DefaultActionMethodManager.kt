/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.support.MultiTransformationAction
import com.embabel.agent.api.common.support.expandInputBindings
import com.embabel.agent.core.Action
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ToolGroupRequirement
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallback
import org.springframework.core.KotlinDetector
import org.springframework.stereotype.Component
import org.springframework.util.ReflectionUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.kotlinFunction

/**
 * Implementation that creates dummy instances of domain objects to discover tools,
 * before re-reading the tool callbacks from the actual domain object instances at invocation time.
 */
@Component
internal class DefaultActionMethodManager(
    val nameGenerator: MethodDefinedOperationNameGenerator = MethodDefinedOperationNameGenerator(),
) : ActionMethodManager {

    private val logger = LoggerFactory.getLogger(DefaultActionMethodManager::class.java)

    @Suppress("UNCHECKED_CAST")
    override fun createAction(
        method: Method,
        instance: Any,
        toolCallbacksOnInstance: List<ToolCallback>,
    ): Action {
        requireNonAmbiguousParameters(method)
        val actionAnnotation = method.getAnnotation(com.embabel.agent.api.annotation.Action::class.java)
        val inputClasses = method.parameters
            .map { it.type }
        val inputs = if (KotlinDetector.isKotlinReflectPresent()) {
            val kFunction = method.kotlinFunction
            if (kFunction != null) kotlinReflectInputs(kFunction) else javaReflectInputs(method)
        } else {
            javaReflectInputs(method)
        }

        return MultiTransformationAction(
            name = nameGenerator.generateName(instance, method.name),
            description = actionAnnotation.description.ifBlank { method.name },
            cost = actionAnnotation.cost,
            inputs = inputs.toSet(),
            canRerun = actionAnnotation.canRerun,
            pre = actionAnnotation.pre.toList(),
            post = actionAnnotation.post.toList(),
            inputClasses = inputClasses,
            outputClass = method.returnType as Class<Any>,
            outputVarName = actionAnnotation.outputBinding,
            toolGroups = (actionAnnotation.toolGroupRequirements.map { ToolGroupRequirement(it.role) } + actionAnnotation.toolGroups.map {
                ToolGroupRequirement(
                    it
                )
            }).toSet(),
        ) { context ->
            invokeActionMethod(
                method = method,
                instance = instance,
                actionContext = context,
            )
        }
    }

    private fun kotlinReflectInputs(
        kFunction: KFunction<*>,
    ): List<IoBinding> {
        return kFunction.valueParameters.filter {
            if (it.type.isMarkedNullable) return@filter false
            val klass = it.type.classifier as? KClass<*> ?: return@filter false
            !isDefaultParameterType(klass.java)
        }.map {
            val nameMatchAnnotation = it.findAnnotation<RequireNameMatch>()
            val name = getBindingParameterName(it.name, nameMatchAnnotation) ?: throw IllegalArgumentException(
                "Name for argument of type [${it.type}] not specified, and parameter name information not " +
                        "available via reflection. Ensure that the compiler uses the '-parameters' flag."
            )

            expandInputBindings(
                name,
                (it.type.classifier as KClass<*>).java
            )
        }.flatten()
    }

    private fun javaReflectInputs(
        method: Method,
    ): List<IoBinding> {
        return method.parameters
            .filterNot {
                isDefaultParameterType(it.type)
            }
            .map {
                val nameMatchAnnotation = it.getAnnotation(RequireNameMatch::class.java)
                val parameterName = if (it.isNamePresent) it.name else null
                val name =
                    getBindingParameterName(parameterName, nameMatchAnnotation) ?: throw IllegalArgumentException(
                        "Name for argument of type [${it.type}] not specified, and parameter name information not " +
                                "available via reflection. Ensure that the kotlinc compiler uses the '-java-parameters' flag, " +
                                "and that the javac compiler uses the '-parameters' flag."
                    )

                expandInputBindings(
                    name,
                    it.type
                )
            }
            .flatten()
    }

    private fun isDefaultParameterType(clazz: Class<*>): Boolean {
        return OperationContext::class.java.isAssignableFrom(clazz) ||
                ProcessContext::class.java.isAssignableFrom(clazz) ||
                Ai::class.java.isAssignableFrom(clazz)
    }

    override fun <O> invokeActionMethod(
        method: Method,
        instance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        logger.debug("Invoking action method {} with payload {}", method.name, actionContext.input)
        val result = if (KotlinDetector.isKotlinReflectPresent()) {
            val kFunction = method.kotlinFunction
            if (kFunction != null) invokeActionMethodKotlinReflect(kFunction, instance, actionContext)
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
        kFunction: KFunction<*>,
        instance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        val args = mutableListOf<Any?>(instance)
        for (parameter in kFunction.valueParameters) {
            val classifier = parameter.type.classifier
            if (classifier is KClass<*>) {
                if (!handleDefaultParameter(args, classifier.java, actionContext)) {
                    val requireNameMatch = parameter.findAnnotation<RequireNameMatch>()
                    val variable = getBindingParameterName(parameter.name, requireNameMatch)
                        ?: error("Parameter name should be available")
                    val lastArg = actionContext.getValue(
                        variable = variable,
                        type = classifier.java.name,
                        dataDictionary = actionContext.processContext.agentProcess.agent,
                    )
                    if (lastArg == null) {
                        val isNullable = parameter.isOptional || parameter.type.isMarkedNullable
                        if (!isNullable) {
                            error("Action ${actionContext.action.name}: Internal error. No value found in blackboard for non-nullable parameter ${parameter.name}:${classifier.java.name}")
                        }
                    }
                    args += lastArg
                }
            }
        }

        val result = try {
            try {
                kFunction.isAccessible = true
                kFunction.call(*args.toTypedArray())
            } catch (ite: InvocationTargetException) {
                ReflectionUtils.handleInvocationTargetException(ite)
            }
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
        val args = mutableListOf<Any?>()
        for (parameter in method.parameters) {
            if (!handleDefaultParameter(args, parameter.type, actionContext)) {
                val requireNameMatch = parameter.getAnnotation(RequireNameMatch::class.java)
                val variable = getBindingParameterName(parameter.name, requireNameMatch)
                    ?: error("Parameter name should be available")
                val lastArg = actionContext.getValue(
                    variable = variable,
                    type = parameter.type.name,
                    dataDictionary = actionContext.processContext.agentProcess.agent,
                )
                args += lastArg
            }
        }

        val result = try {
            method.trySetAccessible()
            ReflectionUtils.invokeMethod(method, instance, *args.toTypedArray())
        } catch (awe: AwaitableResponseException) {
            handleAwaitableResponseException(instance.javaClass.name, method.name, awe)
        } catch (t: Throwable) {
            handleThrowable(instance.javaClass.name, method.name, t)
        }
        return result as O
    }

    private fun handleDefaultParameter(
        args: MutableList<Any?>,
        type: Class<*>,
        actionContext: TransformationActionContext<List<Any>, *>
    ): Boolean {
        if (ProcessContext::class.java.isAssignableFrom(type)) {
            args += actionContext.processContext
            return true
        } else if (OperationContext::class.java.isAssignableFrom(type)) {
            args += actionContext
            return true
        } else if (Ai::class.java.isAssignableFrom(type)) {
            args += actionContext.ai()
            return true
        } else return false
    }

    private fun handleAwaitableResponseException(
        instanceName: String,
        methodName: String,
        awe: AwaitableResponseException
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

    private fun handleThrowable(
        instanceName: String,
        methodName: String,
        t: Throwable
    ) {
        logger.warn(
            "Error invoking action method {}.{}: {}",
            instanceName,
            methodName,
            t.message,
        )
        throw t
    }

}
