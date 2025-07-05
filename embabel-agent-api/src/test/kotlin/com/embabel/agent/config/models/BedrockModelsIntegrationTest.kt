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
package com.embabel.agent.config.models

import com.embabel.agent.config.models.BedrockModels.Companion.EU_ANTHROPIC_CLAUDE_3_7_SONNET
import com.embabel.agent.config.models.BedrockModels.Companion.EU_ANTHROPIC_CLAUDE_OPUS_4
import com.embabel.agent.config.models.BedrockModels.Companion.EU_ANTHROPIC_CLAUDE_SONNET_4
import com.embabel.common.ai.model.AiModel
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.Llm
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.ai.bedrock.cohere.api.CohereEmbeddingBedrockApi.CohereEmbeddingModel.COHERE_EMBED_ENGLISH_V3
import org.springframework.ai.bedrock.cohere.api.CohereEmbeddingBedrockApi.CohereEmbeddingModel.COHERE_EMBED_MULTILINGUAL_V3
import org.springframework.ai.bedrock.titan.api.TitanEmbeddingBedrockApi.TitanEmbeddingModel.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles(value = ["test", "bedrock"])
class MisconfiguredBedrockModelsIntegrationTest {

    @Autowired(required = false)
    private lateinit var bedrockModels: BedrockModels

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `should register Bedrock models when profile is active`() {
        // Verify the bean exists
        assertNotNull(bedrockModels)

        // No bedrock models were registered
        val bedrockModelBeans =
            applicationContext.getBeanNamesForType(AiModel::class.java).filter { it.startsWith("bedrockModel-") }

        assertTrue(bedrockModelBeans.isEmpty())
    }
}

@SpringBootTest(
    properties = [
        "spring.ai.bedrock.aws.region=eu-west-3",
        "spring.ai.bedrock.aws.access-key=AWSACCESSKEYID",
        "spring.ai.bedrock.aws.secret-key=AWSSECRETACCESSKEY",
    ]
)
@ActiveProfiles(value = ["test", "bedrock"])
class BedrockModelsIntegrationDefaultTest {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `should register Titan and Cohere embedding services when 'bedrock' profile is active`() {
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

        val bedrockLlmBeanNames = applicationContext.getBeanNamesForType(Llm::class.java)
            .filter { it.startsWith("bedrockModel-") }
        assertTrue(bedrockLlmBeanNames.isEmpty())
    }
}

@SpringBootTest(
    properties = [
        "embabel.models.default-llm=${EU_ANTHROPIC_CLAUDE_SONNET_4}",
        "embabel.models.llms.cheapest=${EU_ANTHROPIC_CLAUDE_SONNET_4}",
        "embabel.models.llms.best=${EU_ANTHROPIC_CLAUDE_OPUS_4}",
        "spring.ai.bedrock.aws.region=eu-west-3",
        "spring.ai.bedrock.aws.access-key=AWSACCESSKEYID",
        "spring.ai.bedrock.aws.secret-key=AWSSECRETACCESSKEY",
    ]
)
@ActiveProfiles(value = ["test", "bedrock"])
class BedrockModelsIntegrationTest {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `should register only configured Llms when 'bedrock' profile is active`() {
        val bedrockLlmsNames = applicationContext.getBeanNamesForType(Llm::class.java)
            .filter { it.startsWith("bedrockModel-") }
            .map { applicationContext.getBean(it, Llm::class.java) }
            .map { it.name }

        assertTrue(bedrockLlmsNames.containsAll(listOf(EU_ANTHROPIC_CLAUDE_SONNET_4, EU_ANTHROPIC_CLAUDE_OPUS_4)))
        assertFalse(bedrockLlmsNames.contains(EU_ANTHROPIC_CLAUDE_3_7_SONNET))
    }
}
