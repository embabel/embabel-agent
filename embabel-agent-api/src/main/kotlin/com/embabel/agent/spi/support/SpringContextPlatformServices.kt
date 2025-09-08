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
package com.embabel.agent.spi.support

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.channel.OutputChannel
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.event.RagEventListener
import com.embabel.agent.rag.RagService
import com.embabel.agent.rag.RagServiceEnhancer
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.OperationScheduler
import com.embabel.agent.spi.PlatformServices
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.BeansException
import org.springframework.context.ApplicationContext

data class SpringContextPlatformServices(
    override val agentPlatform: AgentPlatform,
    override val llmOperations: LlmOperations,
    override val eventListener: AgenticEventListener,
    override val operationScheduler: OperationScheduler,
    override val asyncer: Asyncer,
    override val ragService: RagService,
    override val objectMapper: ObjectMapper,
    override val outputChannel: OutputChannel,
    override val templateRenderer: TemplateRenderer,
    private val applicationContext: ApplicationContext?,
) : PlatformServices {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun withEventListener(agenticEventListener: AgenticEventListener): PlatformServices {
        return copy(
            eventListener = AgenticEventListener.of(eventListener, agenticEventListener)
        )
    }

    // We get this from the context because of circular dependencies
    override fun autonomy(): Autonomy {
        if (applicationContext == null) {
            throw IllegalStateException("Application context is not available, cannot retrieve Autonomy bean.")
        }
        return applicationContext.getBean(Autonomy::class.java)
    }

    override fun modelProvider(): ModelProvider {
        if (applicationContext == null) {
            throw IllegalStateException("Application context is not available, cannot retrieve ModelProvider bean.")
        }
        return applicationContext.getBean(ModelProvider::class.java)
    }

    /**
     * Create a RagService with the given name, or the default if no name is given.
     * Enhance if necessary
     */
    override fun ragService(
        context: OperationContext,
        serviceName: String?,
        listener: RagEventListener,
    ): RagService? {
        if (applicationContext == null) {
            throw IllegalStateException("Application context is not available, cannot retrieve RagService beans.")
        }

        val delegate = if (serviceName.isNullOrBlank()) {
            ragService
        } else {
            val services = applicationContext.getBeansOfType(RagService::class.java)
            services.values.first { it.name == serviceName } ?: return null
        }

        val ragService = ragServiceEnhancer()
            ?.create(
                delegate = delegate,
                operationContext = context,
                listener = listener,
            ) ?: run {
            logger.info("No RagServiceEnhancer configured, using RagService {} directly", delegate.name)
            delegate
        }
        logger.info(
            "Using RAG service {}",
            ragService.infoString(verbose = context.processContext.processOptions.verbosity.debug)
        )
        return ragService
    }

    private fun ragServiceEnhancer(): RagServiceEnhancer? {
        return try {
            applicationContext?.getBean(RagServiceEnhancer::class.java)
        } catch (_: BeansException) {
            null
        }
    }
}
