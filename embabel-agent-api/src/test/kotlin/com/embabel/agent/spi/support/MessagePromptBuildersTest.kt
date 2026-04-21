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
package com.embabel.agent.spi.support

import com.embabel.chat.AssistantMessage
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.prompt.PromptContributor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for message prompt builder helper functions.
 */
class MessagePromptBuildersTest {

    // ========================================
    // buildPromptContributionsString tests
    // ========================================

    @Test
    fun `buildPromptContributionsString with empty lists returns empty string`() {
        val result = buildPromptContributionsString(emptyList(), emptyList())
        assertEquals("", result)
    }

    @Test
    fun `buildPromptContributionsString joins contributors with separator`() {
        val interactionContributors = listOf(
            testPromptContributor("interaction1"),
            testPromptContributor("interaction2")
        )
        val llmContributors = listOf(
            testPromptContributor("llm1")
        )

        val result = buildPromptContributionsString(interactionContributors, llmContributors)

        assertEquals("interaction1\n----\ninteraction2\n----\nllm1", result)
    }

    @Test
    fun `buildPromptContributionsString with only interaction contributors`() {
        val interactionContributors = listOf(testPromptContributor("only-interaction"))

        val result = buildPromptContributionsString(interactionContributors, emptyList())

        assertEquals("only-interaction", result)
    }

    @Test
    fun `buildPromptContributionsString with only llm contributors`() {
        val llmContributors = listOf(testPromptContributor("only-llm"))

        val result = buildPromptContributionsString(emptyList(), llmContributors)

        assertEquals("only-llm", result)
    }

    private fun testPromptContributor(content: String): PromptContributor = object : PromptContributor {
        override fun contribution(): String = content
    }

    // ========================================
    // partitionMessages tests
    // ========================================

    @Test
    fun `partitionMessages with empty list returns empty results`() {
        val (systemContent, nonSystemMessages) = partitionMessages(emptyList())

        assertTrue(systemContent.isEmpty())
        assertTrue(nonSystemMessages.isEmpty())
    }

    @Test
    fun `partitionMessages separates system messages from others`() {
        val messages = listOf(
            SystemMessage("system1"),
            UserMessage("user1"),
            SystemMessage("system2"),
            AssistantMessage("assistant1"),
            UserMessage("user2")
        )

        val (systemContent, nonSystemMessages) = partitionMessages(messages)

        assertEquals(listOf("system1", "system2"), systemContent)
        assertEquals(3, nonSystemMessages.size)
        assertTrue(nonSystemMessages[0] is UserMessage)
        assertTrue(nonSystemMessages[1] is AssistantMessage)
        assertTrue(nonSystemMessages[2] is UserMessage)
    }

    @Test
    fun `partitionMessages with only system messages`() {
        val messages = listOf(
            SystemMessage("system1"),
            SystemMessage("system2")
        )

        val (systemContent, nonSystemMessages) = partitionMessages(messages)

        assertEquals(listOf("system1", "system2"), systemContent)
        assertTrue(nonSystemMessages.isEmpty())
    }

    @Test
    fun `partitionMessages with no system messages`() {
        val messages = listOf(
            UserMessage("user1"),
            AssistantMessage("assistant1")
        )

        val (systemContent, nonSystemMessages) = partitionMessages(messages)

        assertTrue(systemContent.isEmpty())
        assertEquals(2, nonSystemMessages.size)
    }

    // ========================================
    // buildConsolidatedSystemMessage tests
    // ========================================

    @Test
    fun `buildConsolidatedSystemMessage with empty contents returns empty string`() {
        val result = buildConsolidatedSystemMessage()
        assertEquals("", result)
    }

    @Test
    fun `buildConsolidatedSystemMessage filters empty strings`() {
        val result = buildConsolidatedSystemMessage("content1", "", "content2", "")
        assertEquals("content1\n\ncontent2", result)
    }

    @Test
    fun `buildConsolidatedSystemMessage joins with double newlines`() {
        val result = buildConsolidatedSystemMessage("first", "second", "third")
        assertEquals("first\n\nsecond\n\nthird", result)
    }

    @Test
    fun `buildConsolidatedSystemMessage with single content`() {
        val result = buildConsolidatedSystemMessage("only-content")
        assertEquals("only-content", result)
    }

    @Test
    fun `buildConsolidatedSystemMessage with all empty strings returns empty`() {
        val result = buildConsolidatedSystemMessage("", "", "")
        assertEquals("", result)
    }

    // ========================================
    // buildConsolidatedPromptMessages tests
    // ========================================

    @Test
    fun `buildConsolidatedPromptMessages with empty messages and contributions`() {
        val result = buildConsolidatedPromptMessages(emptyList(), "")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `buildConsolidatedPromptMessages consolidates system messages at beginning`() {
        val messages = listOf(
            UserMessage("user1"),
            SystemMessage("system1"),
            AssistantMessage("assistant1"),
            SystemMessage("system2")
        )

        val result = buildConsolidatedPromptMessages(messages, "contributions")

        // 1 consolidated system + 2 non-system messages = 3
        assertEquals(3, result.size)
        assertTrue(result[0] is SystemMessage)
        assertEquals("contributions\n\nsystem1\n\nsystem2", (result[0] as SystemMessage).content)
        assertTrue(result[1] is UserMessage)
        assertTrue(result[2] is AssistantMessage)
    }

    @Test
    fun `buildConsolidatedPromptMessages with only prompt contributions`() {
        val messages = listOf(
            UserMessage("user1"),
            AssistantMessage("assistant1")
        )

        val result = buildConsolidatedPromptMessages(messages, "prompt-contributions")

        assertEquals(3, result.size)
        assertTrue(result[0] is SystemMessage)
        assertEquals("prompt-contributions", (result[0] as SystemMessage).content)
        assertTrue(result[1] is UserMessage)
        assertTrue(result[2] is AssistantMessage)
    }

    @Test
    fun `buildConsolidatedPromptMessages preserves message order for non-system messages`() {
        val messages = listOf(
            UserMessage("first"),
            AssistantMessage("second"),
            UserMessage("third")
        )

        val result = buildConsolidatedPromptMessages(messages, "sys")

        assertEquals(4, result.size)
        assertEquals("first", (result[1] as UserMessage).content)
        assertEquals("second", (result[2] as AssistantMessage).content)
        assertEquals("third", (result[3] as UserMessage).content)
    }

    @Test
    fun `buildConsolidatedPromptMessages with empty contributions and system messages`() {
        val messages = listOf(
            SystemMessage("system-only"),
            UserMessage("user1")
        )

        val result = buildConsolidatedPromptMessages(messages, "")

        assertEquals(2, result.size)
        assertTrue(result[0] is SystemMessage)
        assertEquals("system-only", (result[0] as SystemMessage).content)
    }
}
