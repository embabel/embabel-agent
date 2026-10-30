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
package com.embabel.agent.config.models.bedrock

import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.APAC_ANTHROPIC_CLAUDE_3_5_HAIKU
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.APAC_ANTHROPIC_CLAUDE_3_5_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.APAC_ANTHROPIC_CLAUDE_3_5_SONNET_V2
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.APAC_ANTHROPIC_CLAUDE_3_7_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.APAC_ANTHROPIC_CLAUDE_HAIKU_4_5
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.APAC_ANTHROPIC_CLAUDE_OPUS_4
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.APAC_ANTHROPIC_CLAUDE_SONNET_4
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.EU_ANTHROPIC_CLAUDE_3_5_HAIKU
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.EU_ANTHROPIC_CLAUDE_3_5_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.EU_ANTHROPIC_CLAUDE_3_5_SONNET_V2
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.EU_ANTHROPIC_CLAUDE_3_7_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.EU_ANTHROPIC_CLAUDE_HAIKU_4_5
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.EU_ANTHROPIC_CLAUDE_OPUS_4
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.EU_ANTHROPIC_CLAUDE_SONNET_4
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.US_ANTHROPIC_CLAUDE_3_5_HAIKU
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.US_ANTHROPIC_CLAUDE_3_5_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.US_ANTHROPIC_CLAUDE_3_5_SONNET_V2
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.US_ANTHROPIC_CLAUDE_3_7_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.US_ANTHROPIC_CLAUDE_HAIKU_4_5
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.US_ANTHROPIC_CLAUDE_OPUS_4
import com.embabel.agent.config.models.bedrock.BedrockModelsConfig.Companion.US_ANTHROPIC_CLAUDE_SONNET_4
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.bedrock.cohere.api.CohereEmbeddingBedrockApi.CohereEmbeddingModel.COHERE_EMBED_ENGLISH_V3
import org.springframework.ai.bedrock.cohere.api.CohereEmbeddingBedrockApi.CohereEmbeddingModel.COHERE_EMBED_MULTILINGUAL_V3
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi.TitanEmbeddingModel.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "embabel.models.default-llm=${EU_ANTHROPIC_CLAUDE_SONNET_4}",
        "embabel.models.llms.cheapest=${EU_ANTHROPIC_CLAUDE_SONNET_4}",
        "embabel.models.llms.best=${EU_ANTHROPIC_CLAUDE_OPUS_4}",
        "embabel.models.default-embedding-model=cohere.embed-multilingual-v3",
        "spring.ai.bedrock.aws.region=eu-west-3",
        "spring.ai.bedrock.aws.access-key=AWSACCESSKEYID",
        "spring.ai.bedrock.aws.secret-key=AWSSECRETACCESSKEY",
    ]
)
class BedrockModelsIntegrationTest {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `should register Bedrock Llms`() {
        val bedrockLlmsNames = applicationContext.getBeanNamesForType(LlmService::class.java)
            .filter { it.startsWith("bedrockModel-") }
            .map { applicationContext.getBean(it, LlmService::class.java) }
            .map { it.name }

        assertTrue(
            bedrockLlmsNames.containsAll(
                listOf(
                    EU_ANTHROPIC_CLAUDE_3_5_SONNET,
                    EU_ANTHROPIC_CLAUDE_3_5_SONNET_V2,
                    EU_ANTHROPIC_CLAUDE_3_5_HAIKU,
                    EU_ANTHROPIC_CLAUDE_3_7_SONNET,
                    EU_ANTHROPIC_CLAUDE_HAIKU_4_5,
                    EU_ANTHROPIC_CLAUDE_SONNET_4,
                    EU_ANTHROPIC_CLAUDE_OPUS_4,
                    US_ANTHROPIC_CLAUDE_3_5_SONNET,
                    US_ANTHROPIC_CLAUDE_3_5_SONNET_V2,
                    US_ANTHROPIC_CLAUDE_3_5_HAIKU,
                    US_ANTHROPIC_CLAUDE_3_7_SONNET,
                    US_ANTHROPIC_CLAUDE_HAIKU_4_5,
                    US_ANTHROPIC_CLAUDE_SONNET_4,
                    US_ANTHROPIC_CLAUDE_OPUS_4,
                    APAC_ANTHROPIC_CLAUDE_3_5_SONNET,
                    APAC_ANTHROPIC_CLAUDE_3_5_SONNET_V2,
                    APAC_ANTHROPIC_CLAUDE_3_5_HAIKU,
                    APAC_ANTHROPIC_CLAUDE_3_7_SONNET,
                    APAC_ANTHROPIC_CLAUDE_HAIKU_4_5,
                    APAC_ANTHROPIC_CLAUDE_SONNET_4,
                    APAC_ANTHROPIC_CLAUDE_OPUS_4
                )
            )
        )
    }

    @Test
    fun `Bedrock usage counts cache tokens reported on top of input tokens`() {
        // Bedrock Converse reports inputTokens excluding cacheReadInputTokens and cacheWriteInputTokens
        val chatModel = mockk<ChatModel>()
        every { chatModel.call(any<Prompt>()) } returns ChatResponse(
            listOf(Generation(AssistantMessage("done"))),
            ChatResponseMetadata.builder().usage(DefaultUsage(30_000, 50, 30_050, null, 18_000L, 2_000L)).build(),
        )
        val llm = applicationContext.getBeansOfType(SpringAiLlmService::class.java).values
            .first { it.name == EU_ANTHROPIC_CLAUDE_SONNET_4 }
            .copy(chatModel = chatModel, pricingModel = PricingModel.usdPer1MTokens(2.0, 10.0))

        val usage = llm.createMessageSender(LlmOptions()).call(listOf(UserMessage("hi")), emptyList()).usage!!

        assertEquals(50_000, usage.promptTokens)
        val invocation = LlmInvocation(
            llmMetadata = llm,
            usage = usage,
            timestamp = Instant.now(),
            runningTime = Duration.ZERO,
        )
        assertEquals(0.1005, invocation.cost(), 1e-12)
    }

    @Test
    fun `should register Titan and Cohere embedding services`() {
        val embeddingServices = applicationContext.getBeanNamesForType(EmbeddingService::class.java)
            .filter { it.startsWith("bedrockModel-") }
            .map<String, EmbeddingService> {
                applicationContext.getBean(it, EmbeddingService::class.java)
            }
        assertTrue(
            embeddingServices.map { it.name }.containsAll(
                listOf(
                    TITAN_EMBED_IMAGE_V1.id(),
                    TITAN_EMBED_TEXT_V1.id(),
                    TITAN_EMBED_TEXT_V2.id(),
                    COHERE_EMBED_MULTILINGUAL_V3.id(),
                    COHERE_EMBED_ENGLISH_V3.id(),
                )
            )
        )
    }
}
