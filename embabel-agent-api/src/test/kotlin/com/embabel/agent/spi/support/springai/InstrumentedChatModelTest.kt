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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.common.ai.model.LlmMetadata
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux

class InstrumentedChatModelTest {

    private val delegate: ChatModel = mockk()
    private val agentProcess: AgentProcess = mockk()
    private val processContext: ProcessContext = mockk(relaxed = true)
    private val interaction: LlmInteraction = mockk()
    private val llmMetadata: LlmMetadata = mockk()
    private val llmRequestEvent: LlmRequestEvent<String> = mockk()

    private lateinit var instrumentedModel: InstrumentedChatModel

    @BeforeEach
    fun setUp() {
        every { llmRequestEvent.agentProcess } returns agentProcess
        every { llmRequestEvent.interaction } returns interaction
        every { llmRequestEvent.outputClass } returns String::class.java
        every { llmRequestEvent.llmMetadata } returns llmMetadata
        every { agentProcess.processContext } returns processContext
        every { interaction.id } returns InteractionId("test-interaction-id")

        instrumentedModel = InstrumentedChatModel(delegate, llmRequestEvent)
    }

    @Nested
    inner class CallTests {

        @Test
        fun `delegates call to underlying ChatModel`() {
            val prompt = Prompt(listOf(UserMessage("Hello")))
            val expectedResponse: ChatResponse = mockk()
            every { delegate.call(prompt) } returns expectedResponse

            val result = instrumentedModel.call(prompt)

            assertThat(result).isSameAs(expectedResponse)
            verify(exactly = 1) { delegate.call(prompt) }
        }

        @Test
        fun `emits ChatModelCallEvent before delegating`() {
            val prompt = Prompt(listOf(UserMessage("Hello")))
            val expectedResponse: ChatResponse = mockk()

            val eventOrder = mutableListOf<String>()
            every { processContext.onProcessEvent(any()) } answers {
                eventOrder.add("event")
            }
            every { delegate.call(any<Prompt>()) } answers {
                eventOrder.add("delegate")
                expectedResponse
            }

            instrumentedModel.call(prompt)

            assertThat(eventOrder).containsExactly("event", "delegate")
        }

        @Test
        fun `emitted event contains the prompt passed to call`() {
            val prompt = Prompt(
                listOf(
                    SystemMessage("You are helpful"),
                    UserMessage("What is 2+2?"),
                )
            )
            val expectedResponse: ChatResponse = mockk()
            every { delegate.call(prompt) } returns expectedResponse

            val eventSlot = slot<ChatModelCallEvent<*>>()
            every { processContext.onProcessEvent(capture(eventSlot)) } just Runs

            instrumentedModel.call(prompt)

            val capturedEvent = eventSlot.captured
            assertThat(capturedEvent.springAiPrompt).isSameAs(prompt)
        }

        @Test
        fun `emitted event carries correct interaction metadata`() {
            val prompt = Prompt(listOf(UserMessage("test")))
            every { delegate.call(prompt) } returns mockk()

            val eventSlot = slot<ChatModelCallEvent<*>>()
            every { processContext.onProcessEvent(capture(eventSlot)) } just Runs

            instrumentedModel.call(prompt)

            val capturedEvent = eventSlot.captured
            assertThat(capturedEvent.interaction).isSameAs(interaction)
            assertThat(capturedEvent.llmMetadata).isSameAs(llmMetadata)
            assertThat(capturedEvent.outputClass).isEqualTo(String::class.java)
        }

        @Test
        fun `propagates exception from delegate`() {
            val prompt = Prompt(listOf(UserMessage("fail")))
            every { delegate.call(prompt) } throws RuntimeException("LLM unavailable")

            assertThrows<RuntimeException> {
                instrumentedModel.call(prompt)
            }
        }

        @Test
        fun `emits event even when delegate throws`() {
            val prompt = Prompt(listOf(UserMessage("fail")))
            every { delegate.call(prompt) } throws RuntimeException("LLM unavailable")

            try {
                instrumentedModel.call(prompt)
            } catch (_: RuntimeException) {
                // expected
            }

            verify(exactly = 1) { processContext.onProcessEvent(any<ChatModelCallEvent<*>>()) }
        }

        @Test
        fun `passes prompt with options through to delegate`() {
            val options: ChatOptions = mockk()
            val prompt = Prompt(listOf(UserMessage("with options")), options)
            every { delegate.call(prompt) } returns mockk()

            instrumentedModel.call(prompt)

            verify { delegate.call(prompt) }
        }
    }

    @Nested
    inner class DefaultOptionsTests {

        @Test
        fun `delegates getDefaultOptions to underlying ChatModel`() {
            val expectedOptions: ChatOptions = mockk()
            every { delegate.defaultOptions } returns expectedOptions

            val result = instrumentedModel.defaultOptions

            assertThat(result).isSameAs(expectedOptions)
        }

        @Test
        fun `does not emit any event`() {
            every { delegate.defaultOptions } returns mockk()

            instrumentedModel.defaultOptions

            verify(exactly = 0) { processContext.onProcessEvent(any()) }
        }
    }

    @Nested
    inner class StreamTests {

        @Test
        fun `delegates stream to underlying ChatModel`() {
            val prompt = Prompt(listOf(UserMessage("stream me")))
            val expectedFlux: Flux<ChatResponse> = Flux.empty()
            every { delegate.stream(prompt) } returns expectedFlux

            val result = instrumentedModel.stream(prompt)

            assertThat(result).isSameAs(expectedFlux)
        }

        @Test
        fun `does not emit event for stream calls`() {
            val prompt = Prompt(listOf(UserMessage("stream me")))
            every { delegate.stream(prompt) } returns Flux.empty()

            instrumentedModel.stream(prompt)

            verify(exactly = 0) { processContext.onProcessEvent(any()) }
        }

        @Test
        fun `stream returns delegate responses`() {
            val prompt = Prompt(listOf(UserMessage("stream me")))
            val response1: ChatResponse = mockk()
            val response2: ChatResponse = mockk()
            every { delegate.stream(prompt) } returns Flux.just(response1, response2)

            val results = instrumentedModel.stream(prompt).collectList().block()

            assertThat(results).containsExactly(response1, response2)
        }
    }

    @Nested
    inner class InterfaceContractTests {

        @Test
        fun `implements ChatModel interface`() {
            assertThat(instrumentedModel).isInstanceOf(ChatModel::class.java)
        }

        @Test
        fun `call with string delegates through call with prompt`() {
            // ChatModel.call(String) is a default method that calls call(Prompt)
            // Since we override call(Prompt), string calls should also be instrumented
            val expectedResponse: ChatResponse = mockk {
                every { result } returns mockk {
                    every { output } returns mockk {
                        every { text } returns "response text"
                    }
                }
            }
            every { delegate.call(any<Prompt>()) } returns expectedResponse

            val result = instrumentedModel.call("simple string input")

            assertThat(result).isEqualTo("response text")
            // Event should fire because call(String) routes through call(Prompt)
            verify(exactly = 1) { processContext.onProcessEvent(any<ChatModelCallEvent<*>>()) }
        }
    }
}
