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
import org.springframework.stereotype.Component
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method
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
        val kFunction = method.kotlinFunction
        val inputs = method.parameters
            .filterNot {
                val kFunctionParameter = kFunction?.parameters?.firstOrNull { kfp -> kfp.name == it.name }
                kFunctionParameter?.type?.isMarkedNullable ?: false
            }
            .filterNot {
                OperationContext::class.java.isAssignableFrom(it.type)
            }
            .map {
                val nameMatchAnnotation = it.getAnnotation(RequireNameMatch::class.java)
                expandInputBindings(
                    if (nameMatchAnnotation != null) it.name else IoBinding.Companion.DEFAULT_BINDING,
                    it.type
                )
            }
            .flatten()

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

    @Suppress("UNCHECKED_CAST")
    override fun <O> invokeActionMethod(
        method: Method,
        instance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O {
        logger.debug("Invoking action method {} with payload {}", method.name, actionContext.input)
        val kFunction = method.kotlinFunction

        val args = mutableListOf<Any?>()
        for (parameter in method.parameters) {
            when {
                ProcessContext::class.java.isAssignableFrom(parameter.type) -> {
                    args += actionContext.processContext
                }

                OperationContext::class.java.isAssignableFrom(parameter.type) -> {
                    args += actionContext
                }

                else -> {
                    val requireNameMatch = parameter.getAnnotation(RequireNameMatch::class.java)
                    val domainTypes = actionContext.processContext.agentProcess.agent.jvmTypes.map { it.clazz }
                    val variable = if (requireNameMatch != null) {
                        parameter.name
                    } else {
                        IoBinding.DEFAULT_BINDING
                    }
                    val lastArg = actionContext.getValue(
                        variable = variable,
                        type = parameter.type.name,
                        dataDictionary = actionContext.processContext.agentProcess.agent,
                    )
                    if (lastArg == null) {
                        val kParam = kFunction?.parameters?.firstOrNull { kfp -> kfp.name == parameter.name }
                        val isNullable =
                            (kParam?.isOptional == true) || (kParam?.type?.isMarkedNullable == true)
                        if (!isNullable) {
                            error("Action ${actionContext.action.name}: Internal error. No value found in blackboard for non-nullable parameter ${parameter.name}:${parameter.type.name}")
                        }
                    }
                    args += lastArg
                }
            }
        }

        val result = try {
            method.trySetAccessible()
            ReflectionUtils.invokeMethod(method, instance, *args.toTypedArray())
        } catch (awe: AwaitableResponseException) {
            // This is not a failure, but will drive transition to a wait state
            logger.info(
                "Action method {}.{} entering wait state: {}",
                instance.javaClass.name,
                method.name,
                awe.message,
            )
            throw awe
        } catch (t: Throwable) {
            logger.warn(
                "Error invoking action method {}.{}: {}",
                instance.javaClass.name,
                method.name,
                t.message,
            )
            throw t
        }
        logger.debug(
            "Result of invoking action method {} was {}: payload {}",
            method.name,
            result,
            actionContext.input
        )
        return result as O
    }

}
