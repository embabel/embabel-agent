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

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.support.MultiTransformationAction
import com.embabel.agent.core.IoBinding
import org.slf4j.LoggerFactory
import org.springframework.core.KotlinDetector
import org.springframework.util.ReflectionUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.kotlinFunction
import com.embabel.agent.core.Action as CoreAction

/**
 * Creates actions from methods defined in @State classes.
 * Unlike the regular ActionMethodManager, this one handles instantiating the state class
 * from the blackboard at runtime.
 */
internal class StateActionMethodManager(
    private val actionMethodManager: ActionMethodManager,
) {

    private val logger = LoggerFactory.getLogger(StateActionMethodManager::class.java)

    fun createAction(
        method: Method,
        stateClass: Class<*>,
    ): CoreAction {
        requireNonAmbiguousParameters(method)
        val actionAnnotation = method.getAnnotation(Action::class.java)
        val inputClasses = method.parameters.map { it.type }
        val inputs = resolveInputBindings(method)
        // Add the state class itself as an input
        val stateInput = IoBinding(
            name = IoBinding.DEFAULT_BINDING,
            type = stateClass.name,
        )
        val allInputs = inputs + stateInput
        require(method.returnType != null) { "Action method ${method.name} must have a return type" }

        return MultiTransformationAction(
            name = "${stateClass.simpleName}.${method.name}",
            description = actionAnnotation.description.ifBlank { method.name },
            cost = { actionAnnotation.cost },
            inputs = allInputs.toSet(),
            canRerun = actionAnnotation.canRerun,
            clearBlackboard = computeClearBlackboard(method, actionAnnotation),
            pre = actionAnnotation.pre.toList() + computeTriggerPreconditions(method),
            post = actionAnnotation.post.toList(),
            inputClasses = inputClasses + stateClass,
            outputClass = method.returnType,
            outputVarName = actionAnnotation.outputBinding,
            toolGroups = computeToolGroups(actionAnnotation),
        ) { context ->
            invokeStateActionMethod(
                method = method,
                stateClass = stateClass,
                actionContext = context,
            )
        }
    }

    private fun resolveInputBindings(javaMethod: Method): Set<IoBinding> {
        val result = mutableSetOf<IoBinding>()
        val kotlinFunction = if (KotlinDetector.isKotlinReflectPresent()) javaMethod.kotlinFunction else null
        for (i in javaMethod.parameters.indices) {
            val javaParameter = javaMethod.parameters[i]
            val kotlinParameter = kotlinFunction?.valueParameters?.getOrNull(i)
            for (argumentResolver in actionMethodManager.argumentResolvers) {
                if (argumentResolver.supportsParameter(javaParameter, kotlinParameter, null)) {
                    result += argumentResolver.resolveInputBinding(javaParameter, kotlinParameter)
                    break
                }
            }
        }
        return result
    }

    private fun resolveStateInstance(
        context: OperationContext,
        stateClass: Class<*>,
    ): Any {
        return context.processContext.agentProcess.getValue(
            variable = IoBinding.DEFAULT_BINDING,
            type = stateClass.name,
        ) ?: throw IllegalStateException(
            "State instance of type ${stateClass.name} not found in blackboard"
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <O> invokeStateActionMethod(
        method: Method,
        stateClass: Class<*>,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        logger.debug("Invoking state action method {}.{}", stateClass.simpleName, method.name)
        // First, get the state instance from the blackboard

        val stateInstance = resolveStateInstance(
            context = actionContext,
            stateClass = stateClass,
        )

        // TODO Arjen to review reflection usage

        // Re-lookup the method from the actual instance's class to handle classloader differences
        // (e.g., when using Spring DevTools hot reload, the class may have been reloaded)
        val actualMethod = if (stateInstance.javaClass == stateClass) {
            method
        } else {
            logger.debug(
                "State class mismatch: expected {} but got {}. Re-looking up method.",
                stateClass.name,
                stateInstance.javaClass.name,
            )
            stateInstance.javaClass.getMethod(method.name, *method.parameterTypes)
        }

        val result = if (KotlinDetector.isKotlinReflectPresent()) {
            val kFunction = actualMethod.kotlinFunction
            if (kFunction != null) invokeStateActionMethodKotlinReflect(
                actualMethod,
                kFunction,
                stateInstance,
                actionContext
            )
            else invokeStateActionMethodJavaReflect(actualMethod, stateInstance, actionContext)
        } else {
            invokeStateActionMethodJavaReflect(actualMethod, stateInstance, actionContext)
        }
        logger.debug(
            "Result of invoking state action method {}.{} was {}",
            stateClass.simpleName,
            method.name,
            result,
        )
        return result
    }

    private fun <O> invokeStateActionMethodKotlinReflect(
        method: Method,
        kFunction: KFunction<*>,
        stateInstance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        val args = arrayOfNulls<Any?>(method.parameters.size + 1)
        args[0] = stateInstance
        for (i in method.parameters.indices) {
            val javaParameter = method.parameters[i]
            val kotlinParameter = kFunction.valueParameters.getOrNull(i)
            val classifier = kotlinParameter?.type?.classifier
            if (classifier is KClass<*>) {
                for (argumentResolver in actionMethodManager.argumentResolvers) {
                    if (argumentResolver.supportsParameter(javaParameter, kotlinParameter, actionContext)) {
                        val arg = argumentResolver.resolveArgument(javaParameter, kotlinParameter, actionContext)
                        if (arg == null) {
                            val isNullable = kotlinParameter.isOptional || kotlinParameter.type.isMarkedNullable
                            if (!isNullable) {
                                error("Action ${actionContext.action.name}: No value found for non-nullable parameter ${kotlinParameter.name}:${classifier.java.name}")
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
        } catch (t: Throwable) {
            handleThrowable(stateInstance.javaClass.name, kFunction.name, t)
        }
        @Suppress("UNCHECKED_CAST")
        return result as O
    }

    private fun <O> invokeStateActionMethodJavaReflect(
        method: Method,
        stateInstance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        val args = arrayOfNulls<Any?>(method.parameters.size)
        for (i in method.parameters.indices) {
            val parameter = method.parameters[i]
            for (argumentResolver in actionMethodManager.argumentResolvers) {
                if (argumentResolver.supportsParameter(parameter, null, actionContext)) {
                    val arg = argumentResolver.resolveArgument(parameter, null, actionContext)
                    args[i] = arg
                }
            }
        }
        val result = try {
            method.trySetAccessible()
            ReflectionUtils.invokeMethod(method, stateInstance, *args)
        } catch (t: Throwable) {
            handleThrowable(stateInstance.javaClass.name, method.name, t)
        }
        @Suppress("UNCHECKED_CAST")
        return result as O
    }

    private fun handleThrowable(
        instanceName: String,
        methodName: String,
        t: Throwable,
    ) {
        logger.warn(
            "Error invoking state action method {}.{}: {}",
            instanceName,
            methodName,
            t.message,
        )
        throw t
    }
}
