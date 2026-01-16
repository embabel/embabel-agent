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
package com.embabel.agent.api.common.support

import com.embabel.agent.api.common.PromptExecutionDelegate
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.common.core.thinking.ThinkingBlock
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.thinking.ThinkingTagType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DelegatingThinkingOperationsTest {

    private val mockDelegate = mockk<PromptExecutionDelegate>()

    private fun createThinkingOperations(): DelegatingThinkingOperations {
        return DelegatingThinkingOperations(
            delegate = mockDelegate,
        )
    }

    @Nested
    inner class CreateObjectIfPossibleTest {

        @Test
        fun `should delegate to delegate createObjectIfPossibleWithThinking`() {
            val messages = listOf(UserMessage("test prompt"))
            val outputClass = String::class.java
            val thinkingBlocks = listOf(
                ThinkingBlock(
                    content = "test thinking",
                    tagType = ThinkingTagType.TAG,
                    tagValue = "think"
                )
            )
            val expectedResponse = ThinkingResponse<String?>(
                result = "test result",
                thinkingBlocks = thinkingBlocks
            )

            every {
                mockDelegate.createObjectIfPossibleWithThinking(messages, outputClass)
            } returns expectedResponse

            val operations = createThinkingOperations()
            val result = operations.createObjectIfPossible(messages, outputClass)

            verify { mockDelegate.createObjectIfPossibleWithThinking(messages, outputClass) }
            assertEquals(expectedResponse, result)
            assertEquals("test result", result.result)
            assertEquals(1, result.thinkingBlocks.size)
            assertEquals("test thinking", result.thinkingBlocks[0].content)
        }

        @Test
        fun `should handle null result`() {
            val messages = listOf(UserMessage("test prompt"))
            val outputClass = String::class.java
            val thinkingBlocks = listOf(
                ThinkingBlock(
                    content = "Could not create object",
                    tagType = ThinkingTagType.TAG,
                    tagValue = "think"
                )
            )
            val expectedResponse = ThinkingResponse<String?>(
                result = null,
                thinkingBlocks = thinkingBlocks
            )

            every {
                mockDelegate.createObjectIfPossibleWithThinking(messages, outputClass)
            } returns expectedResponse

            val operations = createThinkingOperations()
            val result = operations.createObjectIfPossible(messages, outputClass)

            verify { mockDelegate.createObjectIfPossibleWithThinking(messages, outputClass) }
            assertEquals(expectedResponse, result)
            assertEquals(null, result.result)
        }
    }

    @Nested
    inner class CreateObjectTest {

        @Test
        fun `should delegate to delegate createObjectWithThinking`() {
            val messages = listOf(UserMessage("test prompt"))
            val outputClass = TestItem::class.java
            val thinkingBlocks = listOf(
                ThinkingBlock(
                    content = "Creating test item",
                    tagType = ThinkingTagType.TAG,
                    tagValue = "think"
                )
            )
            val expectedResponse = ThinkingResponse(
                result = TestItem("test", 42),
                thinkingBlocks = thinkingBlocks
            )

            every {
                mockDelegate.createObjectWithThinking(messages, outputClass)
            } returns expectedResponse

            val operations = createThinkingOperations()
            val result = operations.createObject(messages, outputClass)

            verify { mockDelegate.createObjectWithThinking(messages, outputClass) }
            assertEquals(expectedResponse, result)
            assertEquals("test", result.result?.name)
            assertEquals(42, result.result?.value)
        }
    }

    @Nested
    inner class RespondTest {

        @Test
        fun `should delegate to delegate respondWithThinking`() {
            val messages = listOf(UserMessage("What is 2+2?"))
            val thinkingBlocks = listOf(
                ThinkingBlock(
                    content = "2+2 equals 4",
                    tagType = ThinkingTagType.TAG,
                    tagValue = "think"
                )
            )
            val expectedResponse = ThinkingResponse(
                result = AssistantMessage("4"),
                thinkingBlocks = thinkingBlocks
            )

            every { mockDelegate.respondWithThinking(messages) } returns expectedResponse

            val operations = createThinkingOperations()
            val result = operations.respond(messages)

            verify { mockDelegate.respondWithThinking(messages) }
            assertEquals(expectedResponse, result)
            assertEquals("4", result.result?.content)
            assertEquals(1, result.thinkingBlocks.size)
        }
    }

    @Nested
    inner class EvaluateConditionTest {

        @Test
        fun `should delegate to delegate evaluateConditionWithThinking`() {
            val condition = "The user is happy"
            val context = "User said: I'm having a great day!"
            val threshold = 0.8
            val thinkingBlocks = listOf(
                ThinkingBlock(
                    content = "User expressed happiness with 'great day'",
                    tagType = ThinkingTagType.TAG,
                    tagValue = "think"
                )
            )
            val expectedResponse = ThinkingResponse(
                result = true,
                thinkingBlocks = thinkingBlocks
            )

            every {
                mockDelegate.evaluateConditionWithThinking(condition, context, any())
            } returns expectedResponse

            val operations = createThinkingOperations()
            val result = operations.evaluateCondition(condition, context, threshold)

            verify { mockDelegate.evaluateConditionWithThinking(condition, context, any()) }
            assertEquals(expectedResponse, result)
            assertEquals(true, result.result)
        }

        @Test
        fun `should pass default confidence threshold when not specified`() {
            val condition = "Test condition"
            val context = "Test context"
            val thinkingBlocks = listOf(
                ThinkingBlock(
                    content = "Condition not met",
                    tagType = ThinkingTagType.TAG,
                    tagValue = "think"
                )
            )
            val expectedResponse = ThinkingResponse(
                result = false,
                thinkingBlocks = thinkingBlocks
            )

            every {
                mockDelegate.evaluateConditionWithThinking(condition, context, any())
            } returns expectedResponse

            val operations = createThinkingOperations()
            val result = operations.evaluateCondition(condition, context)

            verify { mockDelegate.evaluateConditionWithThinking(condition, context, any()) }
            assertEquals(expectedResponse, result)
        }
    }

    data class TestItem(val name: String, val value: Int)
}
