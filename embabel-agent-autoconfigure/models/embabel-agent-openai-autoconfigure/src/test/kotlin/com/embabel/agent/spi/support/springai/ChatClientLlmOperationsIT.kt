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

import com.embabel.agent.api.dsl.agent
import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.validation.ValidationResult
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest

/**
 * Simple data class for testing structured output extraction.
 */
data class TestPerson(val name: String, val sign: String)

@SpringBootTest
internal class ChatClientLlmOperationsIT {
    @Autowired
    @Qualifier("chatClientLlmOperations")
    private lateinit var llmOperations: LlmOperations

    private val llm = LlmOptions("gpt-4.1-nano")

    // gpt-4.1-mini supports thinking via <think> tag extraction
    private val thinkingLlm = LlmOptions("gpt-4.1-mini")
        .withThinking(Thinking.withExtraction())

    private fun dummyAgentProcess(): AgentProcess =
        IntegrationTestUtils.dummyAgentPlatform().createAgentProcess(
            agent("TestAgent", description = "Test agent") {},
            ProcessOptions(),
            emptyMap()
        )

    // Exercises doTransform -> ToolLoopLlmOperations (previously had a Spring AI fallback path)
    @Nested
    inner class CreateObject {
        // Verifies structured output extraction via Embabel tool loop with clear input
        @Test
        fun `sufficient data`() {
            val result = llmOperations.createObject(
                messages = listOf(
                    UserMessage(
                        """
                Create a person from this user input, extracting their name and star sign:
                You are a wizard who can tell me about the stars. Bob is a Cancer.
                """.trimIndent()
                    )
                ),
                LlmInteraction.using(llm),
                TestPerson::class.java,
                dummyAgentProcess(),
                null,
            )
            assertNotNull(result, "Expected a non-null TestPerson")
            Assertions.assertEquals("Bob", result.name, "Expected TestPerson to be Bob, but got: $result")
            Assertions.assertEquals("Cancer", result.sign, "Expected TestPerson to be Cancer, but got: $result")
        }
    }

    // Exercises doTransform with String output class — the path where AssistantMessageGuardRail IS invoked
    @Nested
    inner class Generate {
        // Verifies that a simple prompt returns a non-blank String via the Embabel tool loop
        @Test
        fun `returns non-blank string`() {
            val result = llmOperations.generate(
                "What is the capital of France? Answer in one word.",
                LlmInteraction.using(llm),
                dummyAgentProcess(),
                null,
            )
            assertTrue(result.isNotBlank(), "Expected a non-blank response, but got: '$result'")
            assertTrue(
                result.contains("Paris", ignoreCase = true),
                "Expected response to contain 'Paris', but got: '$result'"
            )
        }
    }

    // Exercises doTransformIfPossible -> ToolLoopLlmOperations with MaybeReturn pattern
    @Nested
    inner class CreateObjectIfPossible {
        // Verifies structured output extraction succeeds when input contains all required fields
        @Test
        fun `sufficient data`() {
            val r = llmOperations.createObjectIfPossible(
                messages = listOf(
                    UserMessage(
                        """
                Create a person from this user input, extracting their name and star sign:
                You are a wizard who can tell me about the stars. Bob is a Cancer.
                """.trimIndent()
                    )
                ),
                LlmInteraction.using(llm),
                TestPerson::class.java,
                dummyAgentProcess(),
                null,
            )
            assertTrue(r.isSuccess, "Expected to be able to create a TestPerson, but got: $r")
            val starPerson = r.getOrThrow()
            Assertions.assertEquals("Bob", starPerson.name, "Expected TestPerson to be Bob, but got: $starPerson")
            Assertions.assertEquals("Cancer", starPerson.sign, "Expected TestPerson to be Cancer, but got: $starPerson")
        }

        // Verifies graceful failure when input lacks required data for object creation
        @Test
        fun `insufficient data`() {
            val r = llmOperations.createObjectIfPossible(
                messages = listOf(
                    UserMessage(
                        """
                Create a person from this user input, extracting their name and star sign:
                You are a wizard who can tell me about the stars.
                """.trimIndent()
                    )
                ),
                LlmInteraction.using(llm),
                TestPerson::class.java,
                dummyAgentProcess(),
                null,
            )
            assertFalse(r.isSuccess, "Expected not to be able to create a TestPerson, but got: $r")
        }
    }

    // Exercises guardrail invocation on structured output (non-String, non-AssistantMessage)
    @Nested
    inner class GuardRailOnStructuredOutput {
        // Verifies that AssistantMessageGuardRail is invoked for structured object responses
        @Test
        fun `AssistantMessageGuardRail should be invoked for createObject`() {
            val guardRailCalled = mutableListOf<String>()

            val guard = object : AssistantMessageGuardRail {
                override val name = "TrackingGuardRail"
                override val description = "Tracks whether guardrail is called"
                override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                    guardRailCalled.add(input)
                    return ValidationResult.VALID
                }

                override fun validate(response: ThinkingResponse<*>, blackboard: Blackboard) =
                    ValidationResult.VALID
            }

            val result = llmOperations.createObject(
                messages = listOf(UserMessage("Bob is a Cancer. Extract name and star sign.")),
                LlmInteraction(
                    id = InteractionId("guardrail-create-object"),
                    llm = llm,
                    guardRails = listOf(guard),
                ),
                TestPerson::class.java,
                dummyAgentProcess(),
                null,
            )
            assertNotNull(result, "Expected a non-null TestPerson")
            assertTrue(
                guardRailCalled.isNotEmpty(),
                "AssistantMessageGuardRail should have been called for structured output"
            )
        }

        // Verifies that AssistantMessageGuardRail is invoked for createObjectIfPossible
        @Test
        fun `AssistantMessageGuardRail should be invoked for createObjectIfPossible`() {
            val guardRailCalled = mutableListOf<String>()

            val guard = object : AssistantMessageGuardRail {
                override val name = "TrackingGuardRail"
                override val description = "Tracks whether guardrail is called"
                override fun validate(input: String, blackboard: Blackboard): ValidationResult {
                    guardRailCalled.add(input)
                    return ValidationResult.VALID
                }

                override fun validate(response: ThinkingResponse<*>, blackboard: Blackboard) =
                    ValidationResult.VALID
            }

            val r = llmOperations.createObjectIfPossible(
                messages = listOf(UserMessage("Bob is a Cancer. Extract name and star sign.")),
                LlmInteraction(
                    id = InteractionId("guardrail-create-if-possible"),
                    llm = llm,
                    guardRails = listOf(guard),
                ),
                TestPerson::class.java,
                dummyAgentProcess(),
                null,
            )
            assertTrue(r.isSuccess, "Expected to create a TestPerson, but got: $r")
            assertTrue(
                guardRailCalled.isNotEmpty(),
                "AssistantMessageGuardRail should have been called for structured output"
            )
        }
    }

    // Exercises doTransformWithThinking -> ToolLoopLlmOperations (previously had a Spring AI fallback path)
    @Nested
    inner class CreateObjectWithThinking {
        // Verifies structured output + thinking block extraction via Embabel tool loop
        @Test
        fun `returns result with thinking blocks`() {
            val response = llmOperations.createObjectWithThinking(
                messages = listOf(
                    UserMessage(
                        """
                Think step by step about this request using <think> tags.
                Create a person from this user input, extracting their name and star sign:
                Bob is a Cancer.
                """.trimIndent()
                    )
                ),
                LlmInteraction.using(thinkingLlm),
                TestPerson::class.java,
                dummyAgentProcess(),
                null,
            )
            assertNotNull(response, "Expected a non-null ThinkingResponse")
            assertTrue(response.hasResult(), "Expected a successful result")
            val person = response.result!!
            Assertions.assertEquals("Bob", person.name, "Expected TestPerson to be Bob, but got: $person")
            Assertions.assertEquals("Cancer", person.sign, "Expected TestPerson to be Cancer, but got: $person")
        }
    }

}
