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
package com.embabel.agent.config

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.AiBuilder
import com.embabel.agent.api.common.ExecutingOperationContext
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope

/**
 * Support injection of [ExecutingOperationContext] and [Ai] into beans.
 *
 */
@Configuration
class InfrastructureInjectionConfiguration {

    @Bean
    @Scope("prototype")
    fun executingOperationContextFactory(agentPlatform: AgentPlatform): ExecutingOperationContext {
        return createExecutingOperationContext(agentPlatform, ProcessOptions())
    }

    @Bean
    @Scope("prototype")
    fun aiFactory(agentPlatform: AgentPlatform): Ai {
        return createExecutingOperationContext(agentPlatform, ProcessOptions()).ai()
    }

    @Bean
    @Scope("prototype")
    fun aiBuilderFactory(agentPlatform: AgentPlatform): AiBuilder {
        return AiBuilderImpl(agentPlatform, ProcessOptions(verbosity = Verbosity(debug = true)))
    }

}

private fun createExecutingOperationContext(
    agentPlatform: AgentPlatform,
    processOptions: ProcessOptions,
): ExecutingOperationContext {
    val callingClassName = findFirstUserClass()
    val agentForIdOnly = agent(
        name = callingClassName,
        description = "Empty agent for operation context injection into $callingClassName",
    ) {
        // No actions, just a placeholder
    }
    return ExecutingOperationContext(
        name = callingClassName,
        agentProcess = agentPlatform.createAgentProcess(
            agentForIdOnly,
            processOptions = processOptions,
            bindings = emptyMap(),
        ),
    )
}

private fun findFirstUserClass(): String {
    val stackTrace = Thread.currentThread().stackTrace

    return stackTrace
        .firstOrNull { element ->
            element.className.startsWith("com.embabel.agent") &&
                    !element.className.contains("$") && // Avoid inner classes
                    !element.methodName.contains("<init>") // Avoid constructor calls from Spring
        }
        ?.className
        ?.substringAfterLast(".")
        ?: "Unknown"
}

private data class AiBuilderImpl(
    val agentPlatform: AgentPlatform,
    val processOptions: ProcessOptions,
) : AiBuilder {

    override fun withProcessOptions(options: ProcessOptions): AiBuilder =
        copy(processOptions = options)

    override val showPrompts: Boolean
        get() = processOptions.verbosity.showPrompts

    override val showLlmResponses: Boolean
        get() = processOptions.verbosity.showLlmResponses

    override fun ai(): Ai = createExecutingOperationContext(agentPlatform, processOptions).ai()

    override fun withShowPrompts(show: Boolean): AiBuilder =
        copy(processOptions = processOptions.copy(verbosity = processOptions.verbosity.copy(showPrompts = show)))

    override fun withShowLlmResponses(show: Boolean): AiBuilder =
        copy(processOptions = processOptions.copy(verbosity = processOptions.verbosity.copy(showLlmResponses = show)))
}
