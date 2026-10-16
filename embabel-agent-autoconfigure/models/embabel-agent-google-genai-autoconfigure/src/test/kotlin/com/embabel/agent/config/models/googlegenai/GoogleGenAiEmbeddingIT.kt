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
package com.embabel.agent.config.models.googlegenai

import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.autoconfigure.models.googlegenai.AgentGoogleGenAiAutoConfiguration
import com.embabel.common.ai.model.EmbeddingService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.stream.Stream

/**
 * Live test that calls every registered Google GenAI embedding model and checks
 * the returned vector has the declared number of dimensions.
 *
 * Requires `GEMINI_API_KEY` (or `GOOGLE_API_KEY`) to be set; otherwise the whole class is disabled.
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gemini-2.5-flash",
        "embabel.agent.platform.models.googlegenai.max-attempts=1",
        "embabel.agent.platform.models.googlegenai.api-key=\${GOOGLE_API_KEY:\${GEMINI_API_KEY:dummy-key-for-context-loading}}",
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
@ActiveProfiles("google-genai-chat-test")
@Import(
    GoogleGenAiChatTestConfig::class,
    AgentGoogleGenAiAutoConfiguration::class,
)
@EnabledIfEnvironmentVariable(
    named = "GEMINI_API_KEY",
    matches = ".+",
    disabledReason = "Live Google GenAI integration test requires GEMINI_API_KEY (or GOOGLE_API_KEY)",
)
class GoogleGenAiEmbeddingIT(@param:Autowired private val embeddingServices: List<EmbeddingService>) {

    @Test
    fun `default embedding model is registered`() {
        assertTrue(
            GoogleGenAiModels.DEFAULT_TEXT_EMBEDDING_MODEL in googleEmbeddingServices().map { it.name },
            "Default embedding model ${GoogleGenAiModels.DEFAULT_TEXT_EMBEDDING_MODEL} is not registered",
        )
    }

    @TestFactory
    fun `every registered embedding model returns a vector of the declared size`(): Stream<DynamicTest> {
        val services = googleEmbeddingServices()
        assertTrue(services.isNotEmpty(), "Expected at least one Google GenAI embedding model, found none")

        return services.stream().map { service ->
            DynamicTest.dynamicTest("embedding model '${service.name}' returns a vector") {
                val vector = service.embed("Embabel agent framework")
                assertEquals(service.dimensions, vector.size, "Unexpected vector size from ${service.name}")
                assertTrue(vector.any { it != 0f }, "Expected a non-zero vector from ${service.name}")
            }
        }
    }

    private fun googleEmbeddingServices(): List<EmbeddingService> =
        embeddingServices.filter { it.provider == GoogleGenAiModels.PROVIDER }.sortedBy { it.name }
}
