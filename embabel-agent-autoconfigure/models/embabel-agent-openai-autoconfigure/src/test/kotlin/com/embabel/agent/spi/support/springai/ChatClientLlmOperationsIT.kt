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