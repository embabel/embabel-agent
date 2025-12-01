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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.api.channel.DevNullOutputChannel
import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.spi.*
import com.embabel.agent.spi.logging.ColorPalette
import com.embabel.agent.spi.logging.DefaultColorPalette
import com.embabel.agent.spi.logging.LoggingAgenticEventListener
import com.embabel.agent.spi.support.*
import com.embabel.agent.spi.support.springai.DefaultToolDecorator
import com.embabel.common.ai.model.*
import com.embabel.common.core.MobyNameGenerator
import com.embabel.common.core.NameGenerator
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.embabel.common.textio.template.TemplateRenderer
import com.embabel.common.util.StringTransformer
import com.embabel.common.util.loggerFor
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder


/**
 * Core configuration for AgentPlatform
 */
@Configuration
@EnableConfigurationProperties(
    ConfigurableModelProviderProperties::class,
    AgentPlatformProperties::class,
    ProcessRepositoryProperties::class,
)
class AgentPlatformConfiguration(
) {

    /**
     * Used for process id generation
     */
    @Bean
    fun nameGenerator(): NameGenerator = MobyNameGenerator

    @Bean
    fun toolDecorator(
        toolGroupResolver: ToolGroupResolver,
        observationRegistry: ObjectProvider<ObservationRegistry>,
    ): ToolDecorator {
        loggerFor<AgentPlatformConfiguration>().info(
            "Creating default ToolDecorator with toolGroupResolver: {}, observationRegistry: {}",
            toolGroupResolver.infoString(verbose = false),
            observationRegistry,
        )
        return DefaultToolDecorator(
            toolGroupResolver = toolGroupResolver,
            observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
            outputTransformer = StringTransformer(),
        )
    }

    @Bean
    fun templateRenderer(): TemplateRenderer = JinjavaTemplateRenderer()

    /**
     * Fallback if we don't have a more interesting logger
     */
    @Bean
    @ConditionalOnMissingBean(LoggingAgenticEventListener::class)
    fun defaultLogger(): LoggingAgenticEventListener = LoggingAgenticEventListener()

    @Bean
    @Primary
    fun eventListener(listeners: List<AgenticEventListener>): AgenticEventListener =
        AgenticEventListener.from(listeners)


    @Bean
    @ConditionalOnMissingBean(ColorPalette::class)
    fun defaultColorPalette(): ColorPalette = DefaultColorPalette()

    @Bean
    @ConditionalOnMissingBean(name = ["embabelJacksonObjectMapper"])
    fun embabelJacksonObjectMapper(builder: Jackson2ObjectMapperBuilder): ObjectMapper {
        return builder.createXmlMapper(false).build()
    }

    @Bean
    fun ranker(
        llmOperations: LlmOperations,
        rankingProperties: RankingProperties,
    ): Ranker = LlmRanker(
        llmOperations = llmOperations,
        rankingProperties = rankingProperties,
    )

    @Bean
    fun agentProcessRepository(
        processRepositoryProperties: ProcessRepositoryProperties,
    ): AgentProcessRepository = InMemoryAgentProcessRepository(processRepositoryProperties)

    @Bean
    fun contextRepository(
        contextRepositoryProperties: ContextRepositoryProperties,
    ): ContextRepository = InMemoryContextRepository(contextRepositoryProperties)

    @Bean
    fun toolGroupResolver(
        toolGroups: List<ToolGroup>,
        toolGroupProviders: List<List<ToolGroup>>,
    ): ToolGroupResolver {
        val allToolGroups = buildList {
            addAll(toolGroups)
            toolGroupProviders.forEach { addAll(it) }
        }
        return RegistryToolGroupResolver(
            name = "SpringBeansToolGroupResolver",
            allToolGroups
        )
    }

    /**
     * Gets registered as an event listener
     */
    @Bean
    fun toolsStats() = AgenticEventListenerToolsStats()

    @Bean
    fun actionScheduler(): OperationScheduler =
        ProcessOptionsOperationScheduler()

    /**
     * Create a `ModelProvider` bean named `"modelProvider"`.
     *
     * Collects all available `Llm` and `EmbeddingService` beans from the provided
     * [ApplicationContext] and constructs a [ConfigurableModelProvider] configured
     * with the supplied [ConfigurableModelProviderProperties].
     *
     * The parameters `dockerLocalModelsConfig` and `ollamaModelsConfig` are
     * optional markers used to trigger related auto-configuration when present;
     * they are not accessed directly by this method.
     *
     * @param applicationContext the Spring application context used to discover model beans
     * @param properties configuration properties for the model provider
     * @param dockerLocalModelsConfig optional marker bean for docker-local models auto-configuration
     * @param ollamaModelsConfig optional marker bean for Ollama models auto-configuration
     * @return a configured [ModelProvider] instance that exposes discovered LLMs and embedding services
     */
    @Bean(name = ["modelProvider"])
    fun modelProvider(
        applicationContext: ApplicationContext,
        properties: ConfigurableModelProviderProperties,
        @Autowired(required = false)
        @Qualifier("anthropicModelsInitializer") anthropicModelsInitializer: Any?,
        @Autowired(required = false)
        @Qualifier("bedrockModelsInitializer") bedrockModelsInitializer: Any?,
        @Autowired(required = false)
        @Qualifier("dockerLocalModelsInitializer") dockerLocalModelsInitializer: Any?,
        @Autowired(required = false)
        @Qualifier("googleGenAiModelsInitializer") googleGenAiModelsInitializer: Any?,
        @Autowired(required = false)
        @Qualifier("lmStudioModelsInitializer") lmStudioModelsInitializer: Any?,
        @Autowired(required = false)
        @Qualifier("ollamaModelsInitializer") ollamaModelsInitializer: Any?,
        @Autowired(required = false)
        @Qualifier("openAiModelsInitializer") openAiModelsInitializer: Any?,
        @Autowired(required = false)
        @Qualifier("geminiModelsInitializer") geminiModelsInitializer: Any?,
        @Autowired(required = false)
        @Qualifier("mistralAiModelsInitializer") mistralAiModelsInitializer: Any?,

    ): ModelProvider {

        return ConfigurableModelProvider(
            llms = applicationContext.getBeansOfType(Llm::class.java).values.toList(),
            embeddingServices = applicationContext.getBeansOfType(EmbeddingService::class.java).values.toList(),
            properties = properties,
        )
    }

    @Bean
    fun autoLlmSelectionCriteriaResolver(
    ): AutoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT

    @Bean
    fun outputChannel(): OutputChannel {
        return DevNullOutputChannel
    }

}
