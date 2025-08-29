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
package com.embabel.agent.spi.support

import com.embabel.agent.api.common.ToolsStats
import com.embabel.agent.core.*
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.spi.InteractionId
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.spi.PlatformServices
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.DefaultToolDecorator
import com.embabel.agent.spi.support.springai.MaybeReturn
import com.embabel.agent.testing.common.EventSavingAgenticEventListener
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.Llm
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.DefaultChatOptions
import org.springframework.ai.chat.prompt.Prompt

class MutableLlmInvocationHistory : LlmInvocationHistory {
    val invocations = mutableListOf<LlmInvocation>()
    override val llmInvocations: List<LlmInvocation>
        get() = invocations

    override val toolsStats: ToolsStats
        get() = TODO("Not yet implemented")
}

data class SpiPerson(val name: String)

data class WierdPerson(
    val name: String,
    val age: Int,
    val weirdness: String,
)

data class Return(
    val result: Result<*>,
    val capturedPrompt: String,
)

class ChatClientLlmTransformerTest {

    val llmInvocationHistory = MutableLlmInvocationHistory()

    @Nested
    inner class Transform {

        @Nested
        inner class HappyPath {

            @Test
            fun `happy path`() {
                val person = SpiPerson("John")
                val result = runWithPromptReturning(jacksonObjectMapper().writeValueAsString(person))
                assertEquals(person, result)
            }

            @Test
            fun `events emitted`() {
                val ese = EventSavingAgenticEventListener()
                val person = SpiPerson("John")
                val result = runWithPromptReturning(
                    llmReturn = jacksonObjectMapper().writeValueAsString(person),
                    eventListener = ese,
                )
                assertEquals(person, result)
                assertEquals(3, ese.processEvents.size)
            }

            @Test
            fun `records usage`() {
                val person = SpiPerson("John")
                val result = runWithPromptReturning(jacksonObjectMapper().writeValueAsString(person))
                assertEquals(person, result)
                assertTrue(llmInvocationHistory.invocations.isNotEmpty())
            }

        }

        @Nested
        inner class Errors {

            @Test
            @Disabled("Decide on correct behavior")
            fun `non JSON return`() {
                val result = runWithPromptReturning("This ain't no JSON")
            }

            @Test
            @Disabled("Decide on correct behavior")
            fun `irrelevant JSON return`() {
                runWithPromptReturning(
                    jacksonObjectMapper().writeValueAsString(
                        mapOf(
                            "foo" to "bar",
                        ),
                    ),
                )
            }

        }

        fun runWithPromptReturning(
            llmReturn: String,
            eventListener: AgenticEventListener = EventSavingAgenticEventListener(),
        ): Any {
            llmInvocationHistory.invocations.clear()
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.eventListener } returns eventListener
            val mockAgentPlatform = mockk<AgentPlatform>()
            every { mockAgentPlatform.toolGroupResolver } returns RegistryToolGroupResolver("mt", emptyList())
            every { mockPlatformServices.agentPlatform } returns mockAgentPlatform
            val mockAgentProcess = mockk<AgentProcess>()
            every { mockAgentProcess.recordLlmInvocation(any()) } answers {
                llmInvocationHistory.invocations.add(
                    firstArg()
                )
            }
            every { mockAgentProcess.processContext.platformServices } returns mockPlatformServices
            val mockAgent = mockk<Agent>()
            every { mockAgent.name } returns "whatever"
            every { mockAgentProcess.agent } returns mockAgent
            val mockProcessContext = mockk<ProcessContext>()
            every { mockProcessContext.onProcessEvent(any()) } answers { eventListener.onProcessEvent(firstArg()) }
            every { mockProcessContext.platformServices } returns mockPlatformServices
            every { mockProcessContext.agentProcess } returns mockAgentProcess
            every { mockAgentProcess.processContext } returns mockProcessContext
            val mockModelProvider = mockk<ModelProvider>()
            val mockChatModel = mockk<ChatModel>()
            every { mockChatModel.defaultOptions } returns DefaultChatOptions()
            val promptSlot = slot<Prompt>()
            every { mockChatModel.call(capture(promptSlot)) } returns ChatResponse(
                listOf(
                    Generation(AssistantMessage(llmReturn)),
                ),
            )
            every { mockModelProvider.getLlm(any()) } returns Llm(
                "test", "provider", mockChatModel,
                DefaultOptionsConverter
            )

            val transformer = ChatClientLlmOperations(
                mockModelProvider,
                DefaultToolDecorator(),
                JinjavaTemplateRenderer(),
            )
            return transformer.createObject(
                messages = listOf(UserMessage("Say hello")),
                interaction = LlmInteraction(id = InteractionId("test")),
                agentProcess = mockAgentProcess,
                action = null,
                outputClass = SpiPerson::class.java,
            )
        }
    }

    @Nested
    inner class MaybeTransform {

        @Nested
        inner class HappyPath {

            @Test
            fun `happy path`() {
                val person = SpiPerson("John")
                val result = runWithPromptReturning(
                    llmReturn = jacksonObjectMapper().writeValueAsString(
                        MaybeReturn(
                            person
                        )
                    ),
                    outputClass = SpiPerson::class.java,
                )
                assertEquals(Result.success(person), result.result)
            }

            @Test
            fun `events emitted`() {
                val ese = EventSavingAgenticEventListener()
                val person = SpiPerson("John")
                val result = runWithPromptReturning(
                    llmReturn = jacksonObjectMapper().writeValueAsString(
                        MaybeReturn(
                            person
                        )
                    ),
                    eventListener = ese,
                    outputClass = SpiPerson::class.java,
                )
                assertEquals(Result.success(person), result.result)
                assertEquals(3, ese.processEvents.size)
            }

            @Test
            fun `records usage`() {
                val person = SpiPerson("John")
                val result = runWithPromptReturning(
                    llmReturn = jacksonObjectMapper().writeValueAsString(
                        MaybeReturn(
                            person
                        )
                    ),
                    eventListener = EventSavingAgenticEventListener(),
                    outputClass = SpiPerson::class.java,
                )
                assertEquals(Result.success(person), result.result)
                assertTrue(llmInvocationHistory.invocations.isNotEmpty())
            }

            @Test
            fun `schema contains type info`() {
                val ese = EventSavingAgenticEventListener()
                val person = WierdPerson("Marmaduke", 24, "weird")
                val result = runWithPromptReturning(
                    llmReturn = jacksonObjectMapper().writeValueAsString(
                        MaybeReturn(
                            success = person
                        )
                    ),
                    eventListener = ese,
                    outputClass = WierdPerson::class.java,
                )
                assertEquals(Result.success(person), result.result)
                assertTrue(
                    result.capturedPrompt.contains("weirdness"),
                    "Prompt should contain 'weirdness' field:\n${result.capturedPrompt}",
                )
            }

            @Test
            fun `schema contains alternative option`() {
                val ese = EventSavingAgenticEventListener()
                val result = runWithPromptReturning(
                    llmReturn =
                        jacksonObjectMapper().writeValueAsString(
                            MaybeReturn(
                                success = null,
                                failure = "couldn't do it"
                            )
                        ),
                    eventListener = ese,
                    outputClass = WierdPerson::class.java,
                )
                assertTrue(result.result.isFailure)
                assertTrue(
                    result.capturedPrompt.contains("impossible"),
                    "Prompt should contain the word 'impossible':\n${result.capturedPrompt}",
                )
            }

        }

        @Nested
        inner class Errors {

            @Test
            @Disabled("Decide on correct behavior")
            fun `non JSON return`() {
                val result = runWithPromptReturning(
                    llmReturn = "This ain't no JSON",
                    outputClass = SpiPerson::class.java,
                )
            }

            @Test
            @Disabled("Decide on correct behavior")
            fun `irrelevant JSON return`() {
                runWithPromptReturning(
                    llmReturn = jacksonObjectMapper().writeValueAsString(
                        mapOf(
                            "foo" to "bar",
                        ),
                    ),
                    outputClass = SpiPerson::class.java,
                )
            }

        }


        fun runWithPromptReturning(
            llmReturn: String,
            eventListener: AgenticEventListener = EventSavingAgenticEventListener(),
            outputClass: Class<*>,
        ): Return {
            val mockPlatformServices = mockk<PlatformServices>()
            every { mockPlatformServices.eventListener } returns eventListener
            val mockAgentPlatform = mockk<AgentPlatform>()
            every { mockAgentPlatform.toolGroupResolver } returns RegistryToolGroupResolver("mt", emptyList())
            every { mockPlatformServices.agentPlatform } returns mockAgentPlatform
            val mockAgentProcess = mockk<AgentProcess>()
            every { mockAgentProcess.processContext.platformServices } returns mockPlatformServices
            val mockAgent = mockk<Agent>()
            every { mockAgent.name } returns "whatever"
            every { mockAgentProcess.agent } returns mockAgent
            val mockProcessContext = mockk<ProcessContext>()
            every { mockProcessContext.onProcessEvent(any()) } answers { eventListener.onProcessEvent(firstArg()) }
            every { mockProcessContext.platformServices } returns mockPlatformServices
            every { mockProcessContext.agentProcess } returns mockAgentProcess
            every { mockAgentProcess.processContext } returns mockProcessContext
            every { mockAgentProcess.recordLlmInvocation(any()) } answers {
                llmInvocationHistory.invocations.add(
                    firstArg()
                )
            }
            val mockModelProvider = mockk<ModelProvider>()
            val mockChatModel = mockk<ChatModel>()
            every { mockChatModel.defaultOptions } returns DefaultChatOptions()
            val promptSlot = slot<Prompt>()
            every { mockChatModel.call(capture(promptSlot)) } returns ChatResponse(
                listOf(
                    Generation(AssistantMessage(llmReturn)),
                ),
            )
            every { mockModelProvider.getLlm(any()) } returns Llm(
                "test", "provider", mockChatModel,
                DefaultOptionsConverter
            )

            val transformer =
                ChatClientLlmOperations(
                    mockModelProvider,
                    DefaultToolDecorator(),
                    JinjavaTemplateRenderer(),
                )
            val result = transformer.createObjectIfPossible(
                prompt = "Say hello",
                interaction = LlmInteraction(id = InteractionId("test")),
                agentProcess = mockAgentProcess,
                action = null,
                outputClass = outputClass,
            )
            return Return(
                result = result,
                capturedPrompt = promptSlot.captured.toString(),
            )
        }
    }

}
