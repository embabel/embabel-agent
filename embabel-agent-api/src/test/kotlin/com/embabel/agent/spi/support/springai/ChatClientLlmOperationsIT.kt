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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.LlmInteraction
import com.embabel.agent.testing.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.common.ai.model.LlmOptions
import com.embabel.example.simple.horoscope.kotlin.StarPerson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
internal class ChatClientLlmOperationsIT {
    @Autowired
    private lateinit var clientLlmOperations: ChatClientLlmOperations

    private val llm = LlmOptions("gpt-4.1-nano")


    @Nested
    inner class CreateObjectIfPossible {
        @Test
        fun `sufficient data`() {
            val agentProcess =
                dummyAgentPlatform().createAgentProcess(evenMoreEvilWizard(), ProcessOptions(), emptyMap())
            val r = clientLlmOperations.createObjectIfPossible(
                """
                Create a person from this user input, extracting their name and star sign:
                You are a wizard who can tell me about the stars. Bob is a Cancer.
                """.trimIndent(),
                LlmInteraction.using(llm),
                StarPerson::class.java,
                agentProcess,
                null,
            )
            assertTrue(r.isSuccess, "Expected to be able to create a StarPerson, but got: $r")
            val starPerson = r.getOrThrow()
            assertEquals("Bob", starPerson.name, "Expected StarPerson to be Bob, but got: $starPerson")
            assertEquals("Cancer", starPerson.sign, "Expected StarPerson to be Cancer, but got: $starPerson")
        }

        @Test
        fun `insufficient data`() {
            val agentProcess =
                dummyAgentPlatform().createAgentProcess(evenMoreEvilWizard(), ProcessOptions(), emptyMap())
            val r = clientLlmOperations.createObjectIfPossible(
                """
                Create a person from this user input, extracting their name and star sign:
                You are a wizard who can tell me about the stars.
                """.trimIndent(),
                LlmInteraction.using(llm),
                StarPerson::class.java,
                agentProcess,
                null,
            )
            assertFalse(r.isSuccess, "Expected not to be able to create a StarPerson, but got: $r")
        }
    }

}
