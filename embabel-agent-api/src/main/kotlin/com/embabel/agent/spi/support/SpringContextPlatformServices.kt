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
package com.embabel.agent.spi.support

import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.spi.AgentProcessRepository
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.OperationScheduler
import com.embabel.agent.spi.expression.LogicalExpressionParser
import com.embabel.agent.spi.expression.spel.SpelLogicalExpressionParser
import com.embabel.chat.ConversationFactoryProvider
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.context.ApplicationContext

/**
 * Uses Spring ApplicationContext to resolve some beans for platform services.
 * If a custom LogicalExpressionParser is provided, it will be used,
 * otherwise all LogicalExpressionParsers in the context will be combined.
 * A SpelLogicalExpressionParser will be added if not already present.
 */
data class SpringContextPlatformServices(
    override val agentPlatform: AgentPlatform,
    override val llmOperations: LlmOperations,
    override val eventListener: AgenticEventListener,
    override val operationScheduler: OperationScheduler,
    override val agentProcessRepository: AgentProcessRepository,
    override val asyncer: Asyncer,
    override val objectMapper: ObjectMapper,
    override val outputChannel: OutputChannel,
    override val templateRenderer: TemplateRenderer,
    val customLogicalExpressionParser: LogicalExpressionParser? = null,
    private val applicationContext: ApplicationContext?,
) : PlatformServices {

    override val logicalExpressionParser = customLogicalExpressionParser ?: run {
        val parsers = buildList {
            val contextParsers = applicationContext?.getBeansOfType<LogicalExpressionParser>()?.values ?: emptyList()
            addAll(contextParsers)
            if (!contextParsers.any { it is SpelLogicalExpressionParser }) {
                add(SpelLogicalExpressionParser())
            }
        }
        LogicalExpressionParser.of(*parsers.toTypedArray())
    }

    override fun withEventListener(agenticEventListener: AgenticEventListener): PlatformServices {
        return copy(
            eventListener = AgenticEventListener.of(eventListener, agenticEventListener)
        )
    }

    // We get this from the context because of circular dependencies
    override fun autonomy(): Autonomy {
        return requireNotNull(applicationContext) {
            "Application context is not available, cannot retrieve Autonomy bean."
        }.getBean<Autonomy>()
    }

    override fun modelProvider(): ModelProvider {
        return requireNotNull(applicationContext) {
            "Application context is not available, cannot retrieve ModelProvider bean."
        }.getBean<ModelProvider>()
    }

    override fun conversationFactoryProvider(): ConversationFactoryProvider {
        return requireNotNull(applicationContext) {
            "Application context is not available, cannot retrieve ConversationFactoryProvider bean."
        }.getBean<ConversationFactoryProvider>()
    }

}
