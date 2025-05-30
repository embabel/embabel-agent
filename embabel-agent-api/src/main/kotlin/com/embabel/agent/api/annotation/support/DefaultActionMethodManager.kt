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

import com.embabel.agent.api.annotation.RequireNameMatch
import com.embabel.agent.api.common.CreateObjectPromptException
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.support.MultiTransformationAction
import com.embabel.agent.api.common.support.expandInputBindings
import com.embabel.agent.core.Action
import com.embabel.agent.core.IoBinding
import com.embabel.common.ai.model.LlmOptions
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallback
import org.springframework.stereotype.Component
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method

/**
 * Implementation that creates dummy instances of domain objects to discover tools,
 * before re-reading the tool callbacks from the actual domain object instances at invocation time.
 */
@Component
internal class DefaultActionMethodManager(
    val nameGenerator: MethodDefinedOperationNameGenerator = MethodDefinedOperationNameGenerator()
) : ActionMethodManager {

    private val logger = LoggerFactory.getLogger(DefaultActionMethodManager::class.java)

    @Suppress("UNCHECKED_CAST")
    override fun createAction(
        method: Method,
        instance: Any,
        toolCallbacksOnInstance: List<ToolCallback>,
    ): Action {
        val actionAnnotation = method.getAnnotation(com.embabel.agent.api.annotation.Action::class.java)
        val inputClasses = method.parameters
            .map { it.type }
        val inputs = method.parameters
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
        method.parameters
            .filterNot {
                OperationContext::class.java.isAssignableFrom(it.type)
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
            toolGroups = actionAnnotation.toolGroups.toSet(),
        ) { context ->
            invokeActionMethod(
                method = method,
                instance = instance,
                context = context,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <O> invokeActionMethod(
        method: Method,
        instance: Any,
        context: TransformationActionContext<List<Any>, O>,
    ): O {
        logger.debug("Invoking action method {} with payload {}", method.name, context.input)

        var args = context.input.toTypedArray()
        if (method.parameters.any { OperationContext::class.java.isAssignableFrom(it.type) }) {
            // We need to add the payload as the last argument
            args += context
        }
        val result = try {
            ReflectionUtils.invokeMethod(method, instance, *args)
        } catch (cope: CreateObjectPromptException) {
            // This is our own exception to get typesafe prompt execution
            // It is not a failure

            val promptContributors = cope.promptContributors
            val promptRunner = context.promptRunner(
                llm = cope.llm ?: LlmOptions(),
                // Remember to add tool groups from the context to those the exception specified at the call site
                toolGroups = cope.toolGroups + context.toolGroups,
                toolObjects = (cope.toolObjects + context.domainObjectInstances()).distinct(),
                promptContributors = promptContributors,
                generateExamples = cope.generateExamples == true,
            )

            if (cope.requireResult) {
                promptRunner.createObject(
                    prompt = cope.prompt,
                    outputClass = cope.outputClass,
                )
            } else {
                promptRunner.createObjectIfPossible(
                    prompt = cope.prompt,
                    outputClass = context.outputClass as Class<Any>,
                )
            }
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
            context.input
        )
        return result as O
    }

}
