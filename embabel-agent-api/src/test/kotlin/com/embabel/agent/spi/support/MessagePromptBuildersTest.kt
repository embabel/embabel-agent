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
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.Named
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for message prompt builder helper functions.
 */
class MessagePromptBuildersTest {

    /**
     * The warned-contributor set is process-global by design - a permanently slow contributor
     * should warn once for the life of the JVM, not once per call. That makes it shared mutable
     * state between tests, so reset it rather than relying on unique names and execution order.
     */
    @BeforeEach
    fun resetWarnedContributors() {
        resetSlowContributorReporting()
    }

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

    @Test
    fun `buildPromptContributionsString drops blank contributions so no dangling separator`() {
        val interactionContributors = listOf(
            testPromptContributor("real1"),
            testPromptContributor(""),
            testPromptContributor("   "),
            testPromptContributor("real2")
        )

        val result = buildPromptContributionsString(interactionContributors, emptyList())

        // Without filtering this would be "real1\n----\n\n----\n   \n----\nreal2".
        assertEquals("real1\n----\nreal2", result)
    }

    @Test
    fun `buildPromptContributionsString with all blank contributions returns empty string`() {
        val contributors = listOf(testPromptContributor(""), testPromptContributor("  "))

        val result = buildPromptContributionsString(contributors, emptyList())

        assertEquals("", result)
    }

    @Test
    fun `buildPromptContributionsString drops a blank contributor spanning both lists`() {
        val interactionContributors = listOf(testPromptContributor("i1"), testPromptContributor(""))
        val llmContributors = listOf(testPromptContributor(""), testPromptContributor("l1"))

        val result = buildPromptContributionsString(interactionContributors, llmContributors)

        assertEquals("i1\n----\nl1", result)
    }

    private fun testPromptContributor(content: String): PromptContributor = object : PromptContributor {
        override fun contribution(): String = content
    }

    // ========================================
    // slow contributor reporting
    // ========================================

    @Test
    fun `a slow contributor is named in a warning, once`() {
        val logger = LoggerFactory.getLogger("com.embabel.agent.spi.support.PromptContributions")
            as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            // Named, because a name is what makes the warning actionable — a bare decorator
            // class name would not tell the reader which of their contributors to fix.
            val slow = object : PromptContributor, Named {
                override val name = "slow-contributor-${System.nanoTime()}"
                override fun contribution(): String {
                    Thread.sleep(300)
                    return "recalled"
                }
            }

            val first = buildPromptContributionsString(listOf(slow), emptyList())
            val second = buildPromptContributionsString(listOf(slow), emptyList())

            assertEquals("recalled", first)
            assertEquals("recalled", second)
            val warnings = appender.list.filter { it.level == Level.WARN }
            assertEquals(1, warnings.size, "should warn once, not on every call: $warnings")
            assertTrue(
                warnings.single().formattedMessage.contains(slow.name),
                "warning must name the contributor: ${warnings.single().formattedMessage}",
            )
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `a fast contributor produces no warning`() {
        val logger = LoggerFactory.getLogger("com.embabel.agent.spi.support.PromptContributions")
            as ch.qos.logback.classic.Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            buildPromptContributionsString(listOf(testPromptContributor("cheap")), emptyList())

            assertTrue(
                appender.list.none { it.level == Level.WARN },
                "an ordinary contributor must stay silent: ${appender.list}",
            )
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `a repeat offender drops to debug rather than warning again`() {
        withContributionsLogger(Level.DEBUG) { appender ->
            val slow = namedSlowContributor("repeat-offender-${System.nanoTime()}")

            buildPromptContributionsString(listOf(slow), emptyList())
            buildPromptContributionsString(listOf(slow), emptyList())

            assertEquals(1, appender.list.count { it.level == Level.WARN })
            assertTrue(
                appender.list.any { it.level == Level.DEBUG && it.formattedMessage.contains(slow.name) },
                "the second occurrence must still be visible at debug: ${appender.list}",
            )
        }
    }

    @Test
    fun `an unnamed contributor is reported by its class, since that is all there is`() {
        withContributionsLogger { appender ->
            buildPromptContributionsString(listOf(UnnamedSlowContributor()), emptyList())

            val warning = appender.list.single { it.level == Level.WARN }.formattedMessage
            assertTrue(
                warning.contains(UnnamedSlowContributor::class.simpleName!!),
                "warning must identify the contributor somehow: $warning",
            )
        }
    }

    @Test
    fun `a blank name falls back to the class, so the warning is never anonymous`() {
        withContributionsLogger { appender ->
            val slow = object : PromptContributor, Named {
                override val name = "   "
                override fun contribution(): String {
                    Thread.sleep(300)
                    return "recalled"
                }
            }

            buildPromptContributionsString(listOf(slow), emptyList())

            val warning = appender.list.single { it.level == Level.WARN }.formattedMessage
            assertTrue(
                warning.contains("MessagePromptBuildersTest"),
                "a blank name must not produce a nameless warning: $warning",
            )
        }
    }

    @Test
    fun `every contributor is timed at debug, not only the slow ones`() {
        withContributionsLogger(Level.DEBUG) { appender ->
            buildPromptContributionsString(listOf(testPromptContributor("cheap")), emptyList())

            assertTrue(
                appender.list.any { it.level == Level.DEBUG },
                "per-call figures are the trend you need before anything crosses the threshold",
            )
            assertTrue(appender.list.none { it.level == Level.WARN })
        }
    }

    @Test
    fun `the warned-contributor set is bounded, because names can vary per instance`() {
        // A reference named after the user or the query is both the likeliest thing to do I/O
        // and the likeliest thing to have a fresh name every call. Without a cap this set grows
        // for the life of the process.
        val added = (warnedSlowContributors.size until MAX_WARNED_SLOW_CONTRIBUTORS)
            .map { "filler-$it-${System.nanoTime()}" }
        warnedSlowContributors.addAll(added)
        val sizeAtCap = warnedSlowContributors.size
        try {
            withContributionsLogger { appender ->
                buildPromptContributionsString(
                    listOf(namedSlowContributor("over-the-cap-${System.nanoTime()}")),
                    emptyList(),
                )

                val named = appender.list.filter {
                    it.level == Level.WARN && it.formattedMessage.contains("Prompt contributor")
                }
                assertTrue(
                    named.isEmpty(),
                    "past the cap an individual contributor must not get its own warning: $named",
                )
            }
            assertEquals(sizeAtCap, warnedSlowContributors.size, "the set must not have grown")
        } finally {
            warnedSlowContributors.removeAll(added.toSet())
        }
    }

    @Test
    fun `reaching the cap is announced, so the blindness after it is not silent`() {
        // The cap stops unbounded growth but makes every new slow contributor invisible. A
        // deployment that crossed it silently would read the absence of warnings as "nothing is
        // slow" rather than "reporting stopped", which is worse than the leak it prevents.
        warnedSlowContributors.addAll((1..MAX_WARNED_SLOW_CONTRIBUTORS).map { "filler-$it" })

        withContributionsLogger { appender ->
            buildPromptContributionsString(
                listOf(namedSlowContributor("over-the-cap-${System.nanoTime()}")),
                emptyList(),
            )
            buildPromptContributionsString(
                listOf(namedSlowContributor("also-over-the-cap-${System.nanoTime()}")),
                emptyList(),
            )

            val warnings = appender.list.filter { it.level == Level.WARN }
            assertEquals(1, warnings.size, "announce the cap once, not per contributor: $warnings")
            assertTrue(
                warnings.single().formattedMessage.contains("cap"),
                "the warning must say reporting stopped: ${warnings.single().formattedMessage}",
            )
        }
    }

    /**
     * A top-level class rather than an anonymous object, so the class name in the warning is
     * one a reader could actually go and look at.
     */
    private class UnnamedSlowContributor : PromptContributor {
        override fun contribution(): String {
            Thread.sleep(300)
            return "recalled"
        }
    }

    private fun namedSlowContributor(contributorName: String) = object : PromptContributor, Named {
        override val name = contributorName
        override fun contribution(): String {
            Thread.sleep(300)
            return "recalled"
        }
    }

    private fun withContributionsLogger(
        level: Level = Level.INFO,
        block: (ListAppender<ILoggingEvent>) -> Unit,
    ) {
        val logger = LoggerFactory.getLogger("com.embabel.agent.spi.support.PromptContributions")
            as ch.qos.logback.classic.Logger
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = level
        try {
            block(appender)
        } finally {
            logger.level = previousLevel
            logger.detachAppender(appender)
        }
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
