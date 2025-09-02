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
package com.embabel.agent.api.common

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Operation
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.rag.RagService
import com.embabel.agent.spi.PlatformServices
import com.embabel.common.ai.model.*
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.EmbeddingService
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel

class OperationContextAiTest {

    private fun createMockOperationContext(): OperationContext {
        val mockContext = mockk<OperationContext>()
        val mockProcessContext = mockk<ProcessContext>()
        val mockPlatformServices = mockk<PlatformServices>()
        val mockAgentPlatform = mockk<AgentPlatform>()
        val mockOperation = mockk<Operation>()

        every { mockContext.processContext } returns mockProcessContext
        every { mockContext.operation } returns mockOperation
        every { mockProcessContext.platformServices } returns mockPlatformServices
        every { mockPlatformServices.agentPlatform } returns mockAgentPlatform

        return mockContext
    }

    private fun createOperationContextAi(context: OperationContext = createMockOperationContext()): Ai {
        return OperationContextAi(context)
    }

    @Nested
    inner class EmbeddingModelTests {

        @Test
        fun `test withEmbeddingModel with criteria`() {
            val mockContext = createMockOperationContext()
            val mockModelProvider = mockk<ModelProvider>()
            val mockEmbeddingService = mockk<EmbeddingService>()
            val mockEmbeddingModel = mockk<EmbeddingModel>()
            val criteria = ModelSelectionCriteria.byName("test-embedding-model")

            every { mockContext.processContext.platformServices.modelProvider() } returns mockModelProvider
            every { mockModelProvider.getEmbeddingService(criteria) } returns mockEmbeddingService
            every { mockEmbeddingService.model } returns mockEmbeddingModel

            val ai = createOperationContextAi(mockContext)
            val result = ai.withEmbeddingModel(criteria)

            assertEquals(mockEmbeddingModel, result, "Embedding model not returned correctly")
            verify { mockModelProvider.getEmbeddingService(criteria) }
        }

        @Test
        fun `test withEmbeddingModel with string model name`() {
            val mockContext = createMockOperationContext()
            val mockModelProvider = mockk<ModelProvider>()
            val mockEmbeddingService = mockk<EmbeddingService>()
            val mockEmbeddingModel = mockk<EmbeddingModel>()
            val modelName = "test-embedding-model"

            every { mockContext.processContext.platformServices.modelProvider() } returns mockModelProvider
            every { mockModelProvider.getEmbeddingService(any()) } returns mockEmbeddingService
            every { mockEmbeddingService.model } returns mockEmbeddingModel

            val ai = createOperationContextAi(mockContext)
            val result = ai.withEmbeddingModel(modelName)

            assertEquals(mockEmbeddingModel, result, "Embedding model not returned correctly")
            verify {
                mockModelProvider.getEmbeddingService(any())
            }
        }

        @Test
        fun `test withDefaultEmbeddingModel`() {
            val mockContext = createMockOperationContext()
            val mockModelProvider = mockk<ModelProvider>()
            val mockEmbeddingService = mockk<EmbeddingService>()
            val mockEmbeddingModel = mockk<EmbeddingModel>()

            every { mockContext.processContext.platformServices.modelProvider() } returns mockModelProvider
            every { mockModelProvider.getEmbeddingService(DefaultModelSelectionCriteria) } returns mockEmbeddingService
            every { mockEmbeddingService.model } returns mockEmbeddingModel

            val ai = createOperationContextAi(mockContext)
            val result = ai.withDefaultEmbeddingModel()

            assertEquals(mockEmbeddingModel, result, "Default embedding model not returned correctly")
            verify { mockModelProvider.getEmbeddingService(DefaultModelSelectionCriteria) }
        }
    }

    @Nested
    inner class RagServiceTests {

        @Test
        fun `test rag with no parameters`() {
            val mockContext = createMockOperationContext()
            val mockRagService = mockk<RagService>()

            every { mockContext.processContext.platformServices.ragService } returns mockRagService

            val ai = createOperationContextAi(mockContext)
            val result = ai.rag()

            assertEquals(mockRagService, result, "RAG service not returned correctly")
            verify { mockContext.processContext.platformServices.ragService }
        }

        @Test
        fun `test rag with service name when service exists`() {
            val mockContext = createMockOperationContext()
            val mockRagService = mockk<RagService>()
            val serviceName = "custom-rag-service"

            every { mockContext.processContext.platformServices.ragService(serviceName) } returns mockRagService

            val ai = createOperationContextAi(mockContext)
            val result = ai.rag(serviceName)

            assertEquals(mockRagService, result, "Named RAG service not returned correctly")
            verify { mockContext.processContext.platformServices.ragService(serviceName) }
        }

        @Test
        fun `test rag with service name when service does not exist`() {
            val mockContext = createMockOperationContext()
            val serviceName = "non-existent-rag-service"

            every { mockContext.processContext.platformServices.ragService(serviceName) } returns null

            val ai = createOperationContextAi(mockContext)

            val exception = assertThrows(IllegalStateException::class.java) {
                ai.rag(serviceName)
            }

            assertEquals("No RAG service found with name $serviceName", exception.message)
            verify { mockContext.processContext.platformServices.ragService(serviceName) }
        }
    }

    @Nested
    inner class LlmTests {

        @Test
        fun `test withLlm with LlmOptions`() {
            val mockContext = createMockOperationContext()
            val mockPromptRunner = mockk<PromptRunner>()
            val mockModifiedPromptRunner = mockk<PromptRunner>()
            val llmOptions = LlmOptions.withModel("test-model").withTemperature(0.8)

            every { mockContext.promptRunner() } returns mockPromptRunner
            every { mockPromptRunner.withLlm(llmOptions) } returns mockModifiedPromptRunner

            val ai = createOperationContextAi(mockContext)
            val result = ai.withLlm(llmOptions)

            assertEquals(mockModifiedPromptRunner, result, "Prompt runner with LLM options not returned correctly")
            verify { mockContext.promptRunner() }
            verify { mockPromptRunner.withLlm(llmOptions) }
        }

        @Test
        fun `test withLlm with string model name`() {
            val mockContext = createMockOperationContext()
            val mockPromptRunner = mockk<PromptRunner>()
            val mockModifiedPromptRunner = mockk<PromptRunner>()
            val modelName = "gpt-4"

            every { mockContext.promptRunner() } returns mockPromptRunner
            every { mockPromptRunner.withLlm(any<LlmOptions>()) } returns mockModifiedPromptRunner

            val ai = createOperationContextAi(mockContext)
            val result = ai.withLlm(modelName)

            assertEquals(mockModifiedPromptRunner, result, "Prompt runner with model name not returned correctly")
            verify { mockContext.promptRunner() }
            verify {
                mockPromptRunner.withLlm(match<LlmOptions> {
                    it.model == modelName
                })
            }
        }

        @Test
        fun `test withLlmByRole`() {
            val mockContext = createMockOperationContext()
            val mockPromptRunner = mockk<PromptRunner>()
            val mockModifiedPromptRunner = mockk<PromptRunner>()
            val role = "summarization"

            every { mockContext.promptRunner() } returns mockPromptRunner
            every { mockPromptRunner.withLlm(any<LlmOptions>()) } returns mockModifiedPromptRunner

            val ai = createOperationContextAi(mockContext)
            val result = ai.withLlmByRole(role)

            assertEquals(mockModifiedPromptRunner, result, "Prompt runner with LLM role not returned correctly")
            verify { mockContext.promptRunner() }
            verify {
                mockPromptRunner.withLlm(any<LlmOptions>())
            }
        }

        @Test
        fun `test withAutoLlm`() {
            val mockContext = createMockOperationContext()
            val mockPromptRunner = mockk<PromptRunner>()
            val mockModifiedPromptRunner = mockk<PromptRunner>()

            every { mockContext.promptRunner() } returns mockPromptRunner
            every { mockPromptRunner.withLlm(any<LlmOptions>()) } returns mockModifiedPromptRunner

            val ai = createOperationContextAi(mockContext)
            val result = ai.withAutoLlm()

            assertEquals(mockModifiedPromptRunner, result, "Prompt runner with auto LLM not returned correctly")
            verify { mockContext.promptRunner() }
            verify {
                mockPromptRunner.withLlm(match<LlmOptions> {
                    it.criteria == AutoModelSelectionCriteria
                })
            }
        }

        @Test
        fun `test withDefaultLlm`() {
            val mockContext = createMockOperationContext()
            val mockPromptRunner = mockk<PromptRunner>()
            val mockModifiedPromptRunner = mockk<PromptRunner>()

            every { mockContext.promptRunner() } returns mockPromptRunner
            every { mockPromptRunner.withLlm(any<LlmOptions>()) } returns mockModifiedPromptRunner

            val ai = createOperationContextAi(mockContext)
            val result = ai.withDefaultLlm()

            assertEquals(mockModifiedPromptRunner, result, "Prompt runner with default LLM not returned correctly")
            verify { mockContext.promptRunner() }
            verify {
                mockPromptRunner.withLlm(match<LlmOptions> {
                    it.criteria == DefaultModelSelectionCriteria
                })
            }
        }

        @Test
        fun `test withFirstAvailableLlmOf with single model`() {
            val mockContext = createMockOperationContext()
            val mockPromptRunner = mockk<PromptRunner>()
            val mockModifiedPromptRunner = mockk<PromptRunner>()
            val modelName = "gpt-4"

            every { mockContext.promptRunner() } returns mockPromptRunner
            every { mockPromptRunner.withLlm(any<LlmOptions>()) } returns mockModifiedPromptRunner

            val ai = createOperationContextAi(mockContext)
            val result = ai.withFirstAvailableLlmOf(modelName)

            assertEquals(mockModifiedPromptRunner, result, "Prompt runner with first available LLM not returned correctly")
            verify { mockContext.promptRunner() }
            verify {
                mockPromptRunner.withLlm(any<LlmOptions>())
            }
        }

        @Test
        fun `test withFirstAvailableLlmOf with multiple models`() {
            val mockContext = createMockOperationContext()
            val mockPromptRunner = mockk<PromptRunner>()
            val mockModifiedPromptRunner = mockk<PromptRunner>()
            val models = arrayOf("gpt-4", "gpt-3.5-turbo", "claude-3")

            every { mockContext.promptRunner() } returns mockPromptRunner
            every { mockPromptRunner.withLlm(any<LlmOptions>()) } returns mockModifiedPromptRunner

            val ai = createOperationContextAi(mockContext)
            val result = ai.withFirstAvailableLlmOf(*models)

            assertEquals(mockModifiedPromptRunner, result, "Prompt runner with first available LLM not returned correctly")
            verify { mockContext.promptRunner() }
            verify {
                mockPromptRunner.withLlm(any<LlmOptions>())
            }
        }

        @Test
        fun `test withFirstAvailableLlmOf with empty varargs`() {
            val mockContext = createMockOperationContext()
            val mockPromptRunner = mockk<PromptRunner>()
            val mockModifiedPromptRunner = mockk<PromptRunner>()

            every { mockContext.promptRunner() } returns mockPromptRunner
            every { mockPromptRunner.withLlm(any<LlmOptions>()) } returns mockModifiedPromptRunner

            val ai = createOperationContextAi(mockContext)
            val result = ai.withFirstAvailableLlmOf()

            assertEquals(mockModifiedPromptRunner, result, "Prompt runner with empty fallback list not returned correctly")
            verify { mockContext.promptRunner() }
            verify {
                mockPromptRunner.withLlm(any<LlmOptions>())
            }
        }
    }

    @Nested
    inner class IntegrationTests {

        @Test
        fun `test OperationContextAi is created correctly by OperationContext`() {
            val mockContext = createMockOperationContext()
            every { mockContext.ai() } returns OperationContextAi(mockContext)

            val ai = mockContext.ai()

            assertNotNull(ai, "AI should not be null")
            assertTrue(ai is OperationContextAi, "AI should be instance of OperationContextAi")
        }

        @Test
        fun `test multiple calls to same method return consistent results`() {
            val mockContext = createMockOperationContext()
            val mockRagService = mockk<RagService>()

            every { mockContext.processContext.platformServices.ragService } returns mockRagService

            val ai = createOperationContextAi(mockContext)
            val result1 = ai.rag()
            val result2 = ai.rag()

            assertEquals(result1, result2, "Multiple calls to rag() should return same service")
            verify(exactly = 2) { mockContext.processContext.platformServices.ragService }
        }
    }
}
