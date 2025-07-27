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
package com.embabel.agent.config.models.bedrock

import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.APAC_ANTHROPIC_CLAUDE_3_5_HAIKU
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.APAC_ANTHROPIC_CLAUDE_3_5_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.APAC_ANTHROPIC_CLAUDE_3_5_SONNET_V2
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.APAC_ANTHROPIC_CLAUDE_3_7_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.APAC_ANTHROPIC_CLAUDE_OPUS_4
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.APAC_ANTHROPIC_CLAUDE_SONNET_4
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.EU_ANTHROPIC_CLAUDE_3_5_HAIKU
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.EU_ANTHROPIC_CLAUDE_3_5_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.EU_ANTHROPIC_CLAUDE_3_5_SONNET_V2
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.EU_ANTHROPIC_CLAUDE_3_7_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.EU_ANTHROPIC_CLAUDE_OPUS_4
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.EU_ANTHROPIC_CLAUDE_SONNET_4
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.US_ANTHROPIC_CLAUDE_3_5_HAIKU
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.US_ANTHROPIC_CLAUDE_3_5_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.US_ANTHROPIC_CLAUDE_3_5_SONNET_V2
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.US_ANTHROPIC_CLAUDE_3_7_SONNET
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.US_ANTHROPIC_CLAUDE_OPUS_4
import com.embabel.agent.config.models.bedrock.BedrockModels.Companion.US_ANTHROPIC_CLAUDE_SONNET_4
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.Llm
import org.junit.jupiter.api.Test
import org.springframework.ai.bedrock.cohere.api.CohereEmbeddingBedrockApi.CohereEmbeddingModel.COHERE_EMBED_ENGLISH_V3
import org.springframework.ai.bedrock.cohere.api.CohereEmbeddingBedrockApi.CohereEmbeddingModel.COHERE_EMBED_MULTILINGUAL_V3
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi.TitanEmbeddingModel.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
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
@ActiveProfiles(value = ["bedrock"])
class BedrockModelsIntegrationTest {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `should register Bedrock Llms`() {
        val bedrockLlmsNames = applicationContext.getBeanNamesForType(Llm::class.java)
            .filter { it.startsWith("bedrockModel-") }
            .map { applicationContext.getBean(it, Llm::class.java) }
            .map { it.name }

        assertTrue(
            bedrockLlmsNames.containsAll(
                listOf(
                    EU_ANTHROPIC_CLAUDE_3_5_SONNET,
                    EU_ANTHROPIC_CLAUDE_3_5_SONNET_V2,
                    EU_ANTHROPIC_CLAUDE_3_5_HAIKU,
                    EU_ANTHROPIC_CLAUDE_3_7_SONNET,
                    EU_ANTHROPIC_CLAUDE_SONNET_4,
                    EU_ANTHROPIC_CLAUDE_OPUS_4,
                    US_ANTHROPIC_CLAUDE_3_5_SONNET,
                    US_ANTHROPIC_CLAUDE_3_5_SONNET_V2,
                    US_ANTHROPIC_CLAUDE_3_5_HAIKU,
                    US_ANTHROPIC_CLAUDE_3_7_SONNET,
                    US_ANTHROPIC_CLAUDE_SONNET_4,
                    US_ANTHROPIC_CLAUDE_OPUS_4,
                    APAC_ANTHROPIC_CLAUDE_3_5_SONNET,
                    APAC_ANTHROPIC_CLAUDE_3_5_SONNET_V2,
                    APAC_ANTHROPIC_CLAUDE_3_5_HAIKU,
                    APAC_ANTHROPIC_CLAUDE_3_7_SONNET,
                    APAC_ANTHROPIC_CLAUDE_SONNET_4,
                    APAC_ANTHROPIC_CLAUDE_OPUS_4
                )
            )
        )
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
