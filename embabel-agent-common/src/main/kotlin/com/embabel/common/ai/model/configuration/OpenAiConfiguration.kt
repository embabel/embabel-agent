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
package com.embabel.common.ai.model.configuration

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.Llm
import com.embabel.common.util.ExcludeFromJacocoGeneratedReport
import com.embabel.common.util.kotlin.loggerFor
import jakarta.validation.constraints.NotBlank
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.document.MetadataMode
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.*
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.validation.annotation.Validated

@ConfigurationProperties("embabel.openai")
@Validated
data class OpenAiProperties(
    @field:NotBlank(message = "API key cannot be blank")
    val apiKey: String,
    @field:NotBlank(message = "workhorseModel cannot be blank")
    val workhorseModel: String,
    @field:NotBlank(message = "premiumModel cannot be blank")
    val premiumModel: String,
    @field:NotBlank(message = "embeddingModel cannot be blank")
    val embeddingModel: String,
)

class OpenAiAvailable : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val environment = context.environment
        val apiKey = environment.getProperty("OPENAI_API_KEY")
        return apiKey != null && apiKey.isNotBlank()
    }
}

/**
 * OpenAI resources. Load first, so tests can step in
 */
@ExcludeFromJacocoGeneratedReport(reason = "Open AI configuration can't be unit tested")
@Configuration
@Conditional(OpenAiAvailable::class)
@Profile("!test")
internal class OpenAiConfiguration(
    private val properties: OpenAiProperties,
) {

    init {
        loggerFor<OpenAiConfiguration>().info("OpenAI AI models are available")
    }

    private val openAiApi = OpenAiApi.builder().apiKey(properties.apiKey).build()

    @Bean
    fun workhorse(): Llm {
        return Llm(
            name = properties.workhorseModel,
            model = chatModelOf(properties.workhorseModel),
        )
    }

    @Bean
    fun premium(): Llm {
        return Llm(
            name = properties.premiumModel,
            model = chatModelOf(properties.premiumModel),
        )
    }

    private fun chatModelOf(model: String): ChatModel {
        return OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(
                OpenAiChatOptions.builder().model(model).build()
            ).build()
    }

    @Bean
    fun embeddingService(): EmbeddingService {
        val model = OpenAiEmbeddingModel(
            openAiApi,
            MetadataMode.EMBED,
            OpenAiEmbeddingOptions.builder().model(properties.embeddingModel)
                .build(),
        )
        return EmbeddingService(
            name = properties.embeddingModel,
            model = model,
        )
    }
}
