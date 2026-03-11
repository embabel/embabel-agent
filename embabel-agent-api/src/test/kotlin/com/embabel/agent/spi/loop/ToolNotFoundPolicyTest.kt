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
package com.embabel.agent.spi.loop

import com.embabel.agent.api.tool.Tool
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolNotFoundPolicyTest {

    private val tools = listOf(
        MockTool("vectorSearch", "Search", { Tool.Result.text("{}") }),
        MockTool("docs_search", "Docs", { Tool.Result.text("{}") }),
    )

    @Nested
    inner class AutoCorrectionPolicyTest {

        @Test
        fun `returns feedback on first failure`() {
            val policy = AutoCorrectionPolicy()
            val action = policy.handle("bad_tool", tools)
            assertTrue(action is ToolNotFoundAction.FeedbackToModel)
            val feedback = action as ToolNotFoundAction.FeedbackToModel
            assertTrue(feedback.message.contains("bad_tool"))
            assertTrue(feedback.message.contains("vectorSearch"))
        }

        @Test
        fun `throws after max retries exceeded`() {
            val policy = AutoCorrectionPolicy(maxRetries = 2)
            policy.handle("bad", tools) // 1
            policy.handle("bad", tools) // 2
            val action = policy.handle("bad", tools) // 3 > maxRetries=2
            assertTrue(action is ToolNotFoundAction.Throw)
            val thrown = (action as ToolNotFoundAction.Throw).exception
            assertEquals("bad", thrown.requestedTool)
            assertTrue(thrown.availableTools.contains("vectorSearch"))
        }

        @Test
        fun `counter resets on tool found`() {
            val policy = AutoCorrectionPolicy(maxRetries = 2)
            policy.handle("bad", tools) // 1
            policy.handle("bad", tools) // 2
            policy.onToolFound() // reset
            val action = policy.handle("bad", tools) // 1 again
            assertTrue(action is ToolNotFoundAction.FeedbackToModel)
        }

        @Test
        fun `suggests suffix-matched tool name`() {
            val policy = AutoCorrectionPolicy()
            val action = policy.handle("ragbot_vectorSearch", tools)
            val feedback = (action as ToolNotFoundAction.FeedbackToModel).message
            assertTrue(feedback.contains("Did you mean 'vectorSearch'?"))
        }

        @Test
        fun `suggests tool name found via contains match`() {
            val policy = AutoCorrectionPolicy()
            val action = policy.handle("my_vectorSearch_v2", tools)
            val feedback = (action as ToolNotFoundAction.FeedbackToModel).message
            assertTrue(feedback.contains("Did you mean 'vectorSearch'?"))
        }

        @Test
        fun `suggests multiple candidates when more than one match`() {
            val multiTools = listOf(
                MockTool("vectorSearch", "Search", { Tool.Result.text("{}") }),
                MockTool("vectorQuery", "Query", { Tool.Result.text("{}") }),
                MockTool("unrelated", "Other", { Tool.Result.text("{}") }),
            )
            val policy = AutoCorrectionPolicy()
            val action = policy.handle("ragbot_vector_tool", multiTools)
            val feedback = (action as ToolNotFoundAction.FeedbackToModel).message
            assertTrue(feedback.contains("Possible matches:"))
            assertTrue(feedback.contains("vectorSearch"))
            assertTrue(feedback.contains("vectorQuery"))
            assertFalse(feedback.contains("'unrelated'"))
        }

        @Test
        fun `case insensitive contains match`() {
            val policy = AutoCorrectionPolicy()
            val action = policy.handle("VECTORSEARCH", tools)
            val feedback = (action as ToolNotFoundAction.FeedbackToModel).message
            assertTrue(feedback.contains("Did you mean 'vectorSearch'?"))
        }

        @Test
        fun `empty requested name does not suggest matches`() {
            val policy = AutoCorrectionPolicy()
            val action = policy.handle("", tools)
            val feedback = (action as ToolNotFoundAction.FeedbackToModel).message
            assertFalse(feedback.contains("Did you mean"))
            assertFalse(feedback.contains("Possible matches"))
        }

        @Test
        fun `very short requested name does not suggest matches`() {
            val policy = AutoCorrectionPolicy()
            val action = policy.handle("ab", tools)
            val feedback = (action as ToolNotFoundAction.FeedbackToModel).message
            assertFalse(feedback.contains("Did you mean"))
            assertFalse(feedback.contains("Possible matches"))
        }

        @Test
        fun `short tool names are excluded from fuzzy matching`() {
            val shortTools = listOf(
                MockTool("ab", "Short", { Tool.Result.text("{}") }),
                MockTool("vectorSearch", "Search", { Tool.Result.text("{}") }),
            )
            val policy = AutoCorrectionPolicy()
            val action = policy.handle("ab_something", shortTools)
            val feedback = (action as ToolNotFoundAction.FeedbackToModel).message
            assertFalse(feedback.contains("'ab'"))
        }

        @Test
        fun `uses default max retries constant`() {
            assertEquals(3, AutoCorrectionPolicy.DEFAULT_MAX_RETRIES)
        }
    }

    @Nested
    inner class ImmediateThrowPolicyTest {

        @Test
        fun `throws immediately on first call`() {
            val policy = ImmediateThrowPolicy
            val action = policy.handle("bad_tool", tools)
            assertTrue(action is ToolNotFoundAction.Throw)
            val thrown = (action as ToolNotFoundAction.Throw).exception
            assertEquals("bad_tool", thrown.requestedTool)
            assertTrue(thrown.availableTools.contains("vectorSearch"))
            assertTrue(thrown.availableTools.contains("docs_search"))
        }
    }
}
