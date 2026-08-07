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
package com.embabel.agent.core.support

import com.embabel.agent.api.common.InteractionId
import com.embabel.common.core.thinking.ThinkingBlock
import com.embabel.common.core.thinking.ThinkingTagType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals

/**
 * Test for GitHub issue #1309: Serialization issue with LlmInteraction.
 *
 * The problem is that LlmInteraction has:
 * 1. val id: InteractionId (where InteractionId is a value class)
 * 2. fun getId(): String (explicit method for Java compatibility)
 *
 * Jackson sees conflicting getter definitions for property "id":
 * - The mangled getter from the value class property
 * - The explicit getId() method
 */
class LlmInteractionSerializationTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Nested
    inner class ThinkingTagSelectionTest {

        private val blocks = listOf(
            ThinkingBlock("selected", ThinkingTagType.TAG, "reasoning"),
            ThinkingBlock("html", ThinkingTagType.TAG, "div"),
            ThinkingBlock("prefix", ThinkingTagType.PREFIX, "legacy_prefix"),
        )

        @Test
        fun `include retains selected tags and non-tag blocks`() {
            val selection = ThinkingTagSelection(include = setOf("reasoning"))
            assertEquals(listOf(blocks[0], blocks[2]), selection.filter(blocks))
        }

        @Test
        fun `exclude removes matching tags`() {
            val selection = ThinkingTagSelection(exclude = setOf("div"))
            assertEquals(listOf(blocks[0], blocks[2]), selection.filter(blocks))
        }

        @Test
        fun `overlapping include and exclude tags are rejected`() {
            assertThrows<IllegalArgumentException> {
                ThinkingTagSelection(include = setOf("reasoning"), exclude = setOf("reasoning"))
            }
        }

        @Test
        fun `invalid tag names are rejected`() {
            assertThrows<IllegalArgumentException> {
                ThinkingTagSelection(include = setOf("invalid tag"))
            }
            assertThrows<IllegalArgumentException> {
                ThinkingTagSelection(exclude = setOf("a><b"))
            }
        }

        @Test
        fun `missingFrom returns only tags absent as XML open tags in the system prompt`() {
            val selection = ThinkingTagSelection(include = setOf("reasoning", "decision_reasoning"))
            val systemPrompt = "You MUST reason inside <decision_reasoning>...</decision_reasoning> tags."
            assertEquals(setOf("reasoning"), selection.missingFrom(systemPrompt))
        }

        @Test
        fun `missingFrom is empty when all tags appear as XML open tags`() {
            val selection = ThinkingTagSelection(include = setOf("reasoning"))
            assertEquals(emptySet(), selection.missingFrom("Provide reasoning inside <reasoning> tags."))
        }

        @Test
        fun `missingFrom does not treat longer tag names as occurrences`() {
            val selection = ThinkingTagSelection(include = setOf("think"))
            assertEquals(setOf("think"), selection.missingFrom("Reason inside <thinking> tags."))
        }

        @Test
        fun `missingFrom treats self-closing tags as present`() {
            val selection = ThinkingTagSelection(include = setOf("reasoning"))
            assertEquals(emptySet(), selection.missingFrom("Reason inside <reasoning/> tags."))
        }

        @Test
        fun `missingFrom is empty when include is empty`() {
            assertEquals(emptySet(), ThinkingTagSelection().missingFrom("anything"))
        }

        @Test
        fun `exclude does not affect missingFrom`() {
            val selection = ThinkingTagSelection(include = setOf("reasoning"), exclude = setOf("div"))
            assertEquals(emptySet(), selection.missingFrom("Reason inside <reasoning> tags."))
        }

        @Test
        fun `warnIfMissing logs a warning when declared tags are absent`() {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(ThinkingTagSelection::class.java) as Logger
            logger.addAppender(appender)
            try {
                ThinkingTagSelection(include = setOf("missing_tag")).warnIfMissing("no tags here", logger)
                assertTrue(
                    appender.list.any { it.level == Level.WARN && it.formattedMessage.contains("missing_tag") },
                )
            } finally {
                logger.detachAppender(appender)
            }
        }

        @Test
        fun `warnIfMissing does not log when all declared tags are present`() {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(ThinkingTagSelection::class.java) as Logger
            logger.addAppender(appender)
            try {
                ThinkingTagSelection(include = setOf("reasoning")).warnIfMissing("Reason inside <reasoning>", logger)
                assertTrue(
                    appender.list.isEmpty(),
                )
            } finally {
                logger.detachAppender(appender)
            }
        }
    }
    @Test
    fun `LlmInteraction can be serialized to JSON without conflicting getter error`() {
        val interaction = LlmInteraction(
            id = InteractionId("test-interaction"),
        )

        // This should not throw:
        // JsonMappingException: Conflicting getter definitions for property "id"
        val json = objectMapper.writeValueAsString(interaction)

        // Verify the id is present in the JSON
        assert(json.contains("test-interaction")) {
            "JSON should contain the interaction id value"
        }
    }
}
