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

import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.core.streaming.StreamingEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

class DelegatingStreamingOperationsTest {

    private val mockDelegate = mockk<PromptExecutionDelegate>()

    private fun createStreamingOperations(): DelegatingStreamingOperations {
        return DelegatingStreamingOperations(
            delegate = mockDelegate,
        )
    }

    @Nested
    inner class WithPromptTest {

        @Test
        fun `should delegate to withMessages and return new instance`() {
            val prompt = "test prompt"
            val updatedDelegate = mockk<PromptExecutionDelegate>()
            val messagesSlot = slot<List<Message>>()

            every { mockDelegate.withMessages(capture(messagesSlot)) } returns updatedDelegate

            val operations = createStreamingOperations()
            val result = operations.withPrompt(prompt)

            verify { mockDelegate.withMessages(any()) }
            assert(result is DelegatingStreamingOperations)

            val capturedMessages = messagesSlot.captured
            assertEquals(1, capturedMessages.size)
            assert(capturedMessages[0] is UserMessage)
            assertEquals(prompt, (capturedMessages[0] as UserMessage).content)
        }
    }

    @Nested
    inner class WithMessagesTest {

        @Test
        fun `should delegate to withMessages and return new instance`() {
            val messages = listOf(UserMessage("message1"), UserMessage("message2"))
            val updatedDelegate = mockk<PromptExecutionDelegate>()

            every { mockDelegate.withMessages(messages) } returns updatedDelegate

            val operations = createStreamingOperations()
            val result = operations.withMessages(messages)

            verify { mockDelegate.withMessages(messages) }
            assert(result is DelegatingStreamingOperations)
        }
    }

    @Nested
    inner class CreateObjectStreamTest {

        @Test
        fun `should delegate to delegate createObjectStream`() {
            val itemClass = TestItem::class.java
            val mockStream = Flux.just(TestItem("test", 42))

            every { mockDelegate.createObjectStream(itemClass) } returns mockStream

            val operations = createStreamingOperations()
            val result = operations.createObjectStream(itemClass)

            verify { mockDelegate.createObjectStream(itemClass) }

            val firstItem = result.blockFirst()
            assertEquals("test", firstItem?.name)
            assertEquals(42, firstItem?.value)
        }
    }

    @Nested
    inner class CreateObjectStreamWithThinkingTest {

        @Test
        fun `should delegate to delegate createObjectStreamWithThinking`() {
            val itemClass = TestItem::class.java
            val mockStream = Flux.just(
                StreamingEvent.Thinking("Thinking..."),
                StreamingEvent.Object(TestItem("test", 42))
            )

            every { mockDelegate.createObjectStreamWithThinking(itemClass) } returns mockStream

            val operations = createStreamingOperations()
            val result = operations.createObjectStreamWithThinking(itemClass)

            verify { mockDelegate.createObjectStreamWithThinking(itemClass) }

            val items = result.collectList().block()
            assertEquals(2, items?.size)
            assertEquals(true, items?.get(0)?.isThinking())
            assertEquals(true, items?.get(1)?.isObject())
        }
    }

    data class TestItem(val name: String, val value: Int)
}
