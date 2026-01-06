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
package com.embabel.agent.config.models

import com.embabel.agent.api.models.FallbackChatModel
import com.embabel.agent.api.models.withFallback
import com.embabel.common.ai.model.Llm
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FallbackChatModelTest {

    private val prompt = Prompt("foo")
    private val primaryResponse = mockk<ChatResponse>()
    private val fallbackResponse = mockk<ChatResponse>()

    @Nested
    inner class CallMethod {

        @Test
        fun `should use primary model when it succeeds`() {
            val primaryModel = mockk<ChatModel>()
            val fallbackModel = mockk<ChatModel>()
            every { primaryModel.call(prompt) } returns primaryResponse
            val resilientModel = FallbackChatModel(primaryModel, fallbackModel) { true }

            val result = resilientModel.call(prompt)

            assertSame(primaryResponse, result)
            verify(exactly = 1) { primaryModel.call(prompt) }
        }

        @Test
        fun `should use fallback model when primary fails and predicate returns true`() {
            val primaryModel = mockk<ChatModel>()
            val fallbackModel = mockk<ChatModel>()
            val exception = RuntimeException("Primary model failed")
            every { primaryModel.call(prompt) } throws exception
            every { fallbackModel.call(prompt) } returns fallbackResponse
            val resilientModel = primaryModel.withFallback(fallbackModel) { true }

            val result = resilientModel.call(prompt)

            // Assert
            assertSame(fallbackResponse, result)
            verify(exactly = 1) { primaryModel.call(prompt) }
            verify(exactly = 1) { fallbackModel.call(prompt) }
        }

        @Test
        fun `should rethrow exception when primary fails and predicate returns false`() {
            // Arrange
            val primaryModel = mockk<ChatModel>()
            val fallbackModel = mockk<ChatModel>()
            every { fallbackModel.call(prompt) } returns fallbackResponse
            val exception = RuntimeException("Primary model failed")
            every { primaryModel.call(prompt) } throws exception
            val resilientModel = primaryModel.withFallback(fallbackModel) { false }

            // Act & Assert
            val thrownException = assertThrows<RuntimeException> {
                resilientModel.call(prompt)
            }
            assertEquals("Primary model failed", thrownException.message)
            verify(exactly = 1) { primaryModel.call(prompt) }
        }
    }

    @Nested
    inner class ExtensionFunctions {

        @Test
        fun `withFallback should create FallbackChatModel from ChatModel`() {
            // Arrange
            val predicate: (Throwable) -> Boolean = { true }

            val primaryModel = mockk<ChatModel>()
            val fallbackModel = mockk<ChatModel>()

            // Act
            val result = primaryModel.withFallback(fallbackModel, predicate)

            // Assert
            assertTrue(result is FallbackChatModel)
        }

        @Test
        fun `withFallback should create Llm with fallback model`() {
            // Arrange
            val primaryModel = mockk<ChatModel>()
            val fallbackModel = mockk<ChatModel>()
            val primaryLlm = Llm(
                name = "primary",
                model = primaryModel,
                optionsConverter = mockk(),
                provider = "test",
            )

            val fallbackLlm = Llm(
                name = "fallback",
                model = fallbackModel,
                optionsConverter = mockk(),
                provider = "test",
            )

            val predicate: (Throwable) -> Boolean = { true }

            // Act
            val result = primaryLlm.withFallback(fallbackLlm, predicate)

            // Assert
            assertEquals("primary", result.name)
            assertTrue(result.model is FallbackChatModel, "Result should be fallback model")
        }

        @Test
        fun `withFallback should return original Llm when fallback is null`() {
            // Arrange
            val primaryModel = mockk<ChatModel>()
            val fallbackModel = mockk<ChatModel>()
            val primaryLlm = Llm(
                name = "primary",
                model = primaryModel,
                optionsConverter = mockk(),
                provider = "test",
            )

            val predicate: (Throwable) -> Boolean = { true }

            // Act
            val result = primaryLlm.withFallback(null, predicate)

            // Assert
            assertSame(primaryLlm, result)
        }
    }
}
