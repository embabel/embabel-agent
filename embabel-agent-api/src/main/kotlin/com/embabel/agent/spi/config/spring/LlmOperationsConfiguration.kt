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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.event.observation.AgentInstrumentation
import com.embabel.agent.api.event.observation.InternalObservabilityApi
import com.embabel.agent.api.event.observation.NoOpAgentInstrumentation
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.config.spring.AgentPlatformProperties.LlmOperationsConfig.LlmOperationsType
import com.embabel.agent.spi.loop.ToolLoopFactory
import com.embabel.agent.spi.support.LlmDataBindingProperties
import com.embabel.agent.spi.support.LlmOperationsPromptsProperties
import com.embabel.agent.spi.support.ToolLoopLlmOperations
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.spi.validation.ValidationPromptGenerator
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.common.ai.converters.SchemaGeneratorCustomizer
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.textio.template.TemplateRenderer
import com.embabel.common.util.EmbabelObjectMapperHolder
import jakarta.validation.Validator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionMessage
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Registers [ToolLoopLlmOperations] when `embabel.agent.platform.llm-operations.type=tool-loop`.
 *
 * Use it for [com.embabel.agent.spi.LlmService]s that are not backed by Spring AI: every call goes
 * through the service's own [com.embabel.agent.spi.loop.LlmMessageSender].
 * The default, `spring-ai`, registers [com.embabel.agent.spi.support.springai.ChatClientLlmOperations] instead.
 */
@Configuration
class LlmOperationsConfiguration {

    @OptIn(InternalObservabilityApi::class)
    @Bean
    @Conditional(OnToolLoopLlmOperations::class)
    fun toolLoopLlmOperations(
        modelProvider: ModelProvider,
        toolDecorator: ToolDecorator,
        validator: Validator,
        validationPromptGenerator: ObjectProvider<ValidationPromptGenerator>,
        dataBindingProperties: ObjectProvider<LlmDataBindingProperties>,
        autoLlmSelectionCriteriaResolver: ObjectProvider<AutoLlmSelectionCriteriaResolver>,
        promptsProperties: ObjectProvider<LlmOperationsPromptsProperties>,
        objectMapperHolder: ObjectProvider<EmbabelObjectMapperHolder>,
        instrumentation: ObjectProvider<AgentInstrumentation>,
        asyncer: Asyncer,
        toolLoopFactory: ToolLoopFactory,
        templateRenderer: TemplateRenderer,
        schemaGeneratorCustomizers: ObjectProvider<SchemaGeneratorCustomizer>,
    ): ToolLoopLlmOperations {
        return ToolLoopLlmOperations(
            modelProvider = modelProvider,
            toolDecorator = toolDecorator,
            validator = validator,
            validationPromptGenerator = validationPromptGenerator.getIfAvailable { DefaultValidationPromptGenerator() },
            dataBindingProperties = dataBindingProperties.getIfAvailable { LlmDataBindingProperties() },
            autoLlmSelectionCriteriaResolver = autoLlmSelectionCriteriaResolver
                .getIfAvailable { AutoLlmSelectionCriteriaResolver.DEFAULT },
            promptsProperties = promptsProperties.getIfAvailable { LlmOperationsPromptsProperties() },
            objectMapper = objectMapperHolder.getIfAvailable { EmbabelObjectMapperHolder.createDefault() }.get(),
            instrumentation = instrumentation.getIfAvailable { NoOpAgentInstrumentation },
            asyncer = asyncer,
            toolLoopFactory = toolLoopFactory,
            templateRenderer = templateRenderer,
            schemaGeneratorCustomizers = schemaGeneratorCustomizers.orderedStream().toList(),
        )
    }

    companion object {
        const val LLM_OPERATIONS_PREFIX = "embabel.agent.platform.llm-operations"
    }
}

/**
 * Matches when `embabel.agent.platform.llm-operations.type` binds to [type] (default [LlmOperationsType.SPRING_AI]).
 *
 * Binds the enum rather than comparing strings as `@ConditionalOnProperty` does, so every spelling the
 * property itself accepts (`tool-loop`, `TOOL_LOOP`, `tool_loop`) selects the same bean, and an unknown
 * value fails startup naming the property instead of leaving no `LlmOperations` registered.
 */
internal abstract class LlmOperationsTypeCondition(private val type: LlmOperationsType) : SpringBootCondition() {

    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val configured = Binder.get(context.environment)
            .bind("${LlmOperationsConfiguration.LLM_OPERATIONS_PREFIX}.type", LlmOperationsType::class.java)
            .orElse(LlmOperationsType.SPRING_AI)
        val message = ConditionMessage.forCondition("LlmOperations type $type").because("type is $configured")
        return if (configured == type) ConditionOutcome.match(message) else ConditionOutcome.noMatch(message)
    }
}

/** Selects [com.embabel.agent.spi.support.springai.ChatClientLlmOperations]. */
internal class OnSpringAiLlmOperations : LlmOperationsTypeCondition(LlmOperationsType.SPRING_AI)

/** Selects [ToolLoopLlmOperations]. */
internal class OnToolLoopLlmOperations : LlmOperationsTypeCondition(LlmOperationsType.TOOL_LOOP)
