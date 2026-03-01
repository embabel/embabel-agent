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
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Simple data class for testing structured output extraction.
 */
data class TestPerson(val name: String, val sign: String)

@SpringBootTest
internal class ChatClientLlmOperationsIT {
    @Autowired
    @Qualifier("chatClientLlmOperations")
    private lateinit var llmOperations: LlmOperations

    private val llm = LlmOptions.Companion("gpt-4.1-nano")

    private fun dummyAgentProcess(): AgentProcess =
        IntegrationTestUtils.dummyAgentPlatform().createAgentProcess(
            agent("TestAgent", description = "Test agent") {},
            ProcessOptions(),
            emptyMap()
        )

    @Nested
    inner class CreateObjectIfPossible {
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
                LlmInteraction.Companion.using(llm),
                TestPerson::class.java,
                dummyAgentProcess(),
                null,
            )
            assertTrue(r.isSuccess, "Expected to be able to create a TestPerson, but got: $r")
            val starPerson = r.getOrThrow()
            Assertions.assertEquals("Bob", starPerson.name, "Expected TestPerson to be Bob, but got: $starPerson")
            Assertions.assertEquals("Cancer", starPerson.sign, "Expected TestPerson to be Cancer, but got: $starPerson")
        }

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
                LlmInteraction.Companion.using(llm),
                TestPerson::class.java,
                dummyAgentProcess(),
                null,
            )
            assertFalse(r.isSuccess, "Expected not to be able to create a TestPerson, but got: $r")
        }
    }

}
