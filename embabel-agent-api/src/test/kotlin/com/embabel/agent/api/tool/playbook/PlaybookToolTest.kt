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
package com.embabel.agent.api.tool.playbook

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Blackboard
import com.embabel.common.ai.model.LlmOptions
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Test artifact class.
 */
data class TestDocument(val content: String)

class PlaybookToolTest {

    private fun mockBlackboard(objects: List<Any> = emptyList()): Blackboard = mockk(relaxed = true) {
        every { this@mockk.objects } returns objects
    }

    private fun createTestTool(name: String, result: String = "result"): Tool {
        return mockk<Tool> {
            every { definition } returns object : Tool.Definition {
                override val name: String = name
                override val description: String = "Test tool $name"
                override val inputSchema: Tool.InputSchema = Tool.InputSchema.empty()
            }
            every { metadata } returns Tool.Metadata.DEFAULT
            every { call(any()) } returns Tool.Result.text(result)
        }
    }

    private fun createArtifactTool(name: String, artifact: Any): Tool {
        return mockk<Tool> {
            every { definition } returns object : Tool.Definition {
                override val name: String = name
                override val description: String = "Test tool $name"
                override val inputSchema: Tool.InputSchema = Tool.InputSchema.empty()
            }
            every { metadata } returns Tool.Metadata.DEFAULT
            every { call(any()) } returns Tool.Result.withArtifact("result", artifact)
        }
    }

    @Nested
    inner class Construction {

        @Test
        fun `should create PlaybookTool with name and description`() {
            val tool = PlaybookTool("test", "Test description")

            assertThat(tool.definition.name).isEqualTo("test")
            assertThat(tool.definition.description).isEqualTo("Test description")
            assertThat(tool.unlockedToolCount).isEqualTo(0)
            assertThat(tool.lockedToolCount).isEqualTo(0)
        }

        @Test
        fun `should add unlocked tools with withTools`() {
            val searchTool = createTestTool("search")
            val fetchTool = createTestTool("fetch")

            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool, fetchTool)

            assertThat(playbook.unlockedToolCount).isEqualTo(2)
        }

        @Test
        fun `should add locked tool with curried syntax`() {
            val searchTool = createTestTool("search")
            val analyzeTool = createTestTool("analyze")

            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool)
                .withTool(analyzeTool)(searchTool)

            assertThat(playbook.unlockedToolCount).isEqualTo(1)
            assertThat(playbook.lockedToolCount).isEqualTo(1)
        }

        @Test
        fun `should add locked tool with unlockedBy syntax`() {
            val searchTool = createTestTool("search")
            val analyzeTool = createTestTool("analyze")

            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool)
                .withTool(analyzeTool).unlockedBy(searchTool)

            assertThat(playbook.unlockedToolCount).isEqualTo(1)
            assertThat(playbook.lockedToolCount).isEqualTo(1)
        }

        @Test
        fun `should chain multiple locked tools`() {
            val searchTool = createTestTool("search")
            val analyzeTool = createTestTool("analyze")
            val summarizeTool = createTestTool("summarize")

            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool)
                .withTool(analyzeTool)(searchTool)
                .withTool(summarizeTool)(analyzeTool)

            assertThat(playbook.unlockedToolCount).isEqualTo(1)
            assertThat(playbook.lockedToolCount).isEqualTo(2)
        }

        @Test
        fun `should configure LLM options`() {
            val llmOptions = LlmOptions(temperature = 0.5)

            val playbook = PlaybookTool("test", "Test")
                .withLlm(llmOptions)

            assertThat(playbook.llm.temperature).isEqualTo(0.5)
        }

        @Test
        fun `should configure max iterations`() {
            val playbook = PlaybookTool("test", "Test")
                .withMaxIterations(10)

            assertThat(playbook.maxIterations).isEqualTo(10)
        }

        @Test
        fun `should configure system prompt`() {
            val playbook = PlaybookTool("test", "Test")
                .withSystemPrompt("Custom prompt")

            // Verify by checking the prompt creator returns our custom prompt
            val prompt = playbook.systemPromptCreator(mockk())
            assertThat(prompt).isEqualTo("Custom prompt")
        }
    }

    @Nested
    inner class UnlockConditions {

        @Test
        fun `AfterTools condition should be satisfied when all prerequisites called`() {
            val condition = UnlockCondition.AfterTools("search", "fetch")
            val context = PlaybookContext(
                calledToolNames = setOf("search", "fetch"),
                artifacts = emptyList(),
                iterationCount = 2,
                blackboard = mockBlackboard(),
            )

            assertThat(condition.isSatisfied(context)).isTrue()
        }

        @Test
        fun `AfterTools condition should not be satisfied when prerequisites missing`() {
            val condition = UnlockCondition.AfterTools("search", "fetch")
            val context = PlaybookContext(
                calledToolNames = setOf("search"),
                artifacts = emptyList(),
                iterationCount = 1,
                blackboard = mockBlackboard(),
            )

            assertThat(condition.isSatisfied(context)).isFalse()
        }

        @Test
        fun `OnArtifact condition should be satisfied when artifact type present`() {
            val condition = UnlockCondition.OnArtifact(TestDocument::class.java)
            val context = PlaybookContext(
                calledToolNames = emptySet(),
                artifacts = listOf(TestDocument("content")),
                iterationCount = 1,
                blackboard = mockBlackboard(),
            )

            assertThat(condition.isSatisfied(context)).isTrue()
        }

        @Test
        fun `OnArtifact condition should not be satisfied when artifact type missing`() {
            val condition = UnlockCondition.OnArtifact(TestDocument::class.java)
            val context = PlaybookContext(
                calledToolNames = emptySet(),
                artifacts = listOf("some string"),
                iterationCount = 1,
                blackboard = mockBlackboard(),
            )

            assertThat(condition.isSatisfied(context)).isFalse()
        }

        @Test
        fun `AllOf condition should require all conditions satisfied`() {
            val condition = UnlockCondition.AllOf(
                UnlockCondition.AfterTools("search"),
                UnlockCondition.OnArtifact(TestDocument::class.java),
            )

            val satisfiedContext = PlaybookContext(
                calledToolNames = setOf("search"),
                artifacts = listOf(TestDocument("content")),
                iterationCount = 1,
                blackboard = mockBlackboard(),
            )
            assertThat(condition.isSatisfied(satisfiedContext)).isTrue()

            val partialContext = PlaybookContext(
                calledToolNames = setOf("search"),
                artifacts = emptyList(),
                iterationCount = 1,
                blackboard = mockBlackboard(),
            )
            assertThat(condition.isSatisfied(partialContext)).isFalse()
        }

        @Test
        fun `AnyOf condition should require at least one condition satisfied`() {
            val condition = UnlockCondition.AnyOf(
                UnlockCondition.AfterTools("search"),
                UnlockCondition.AfterTools("fetch"),
            )

            val contextWithSearch = PlaybookContext(
                calledToolNames = setOf("search"),
                artifacts = emptyList(),
                iterationCount = 1,
                blackboard = mockBlackboard(),
            )
            assertThat(condition.isSatisfied(contextWithSearch)).isTrue()

            val contextWithFetch = PlaybookContext(
                calledToolNames = setOf("fetch"),
                artifacts = emptyList(),
                iterationCount = 1,
                blackboard = mockBlackboard(),
            )
            assertThat(condition.isSatisfied(contextWithFetch)).isTrue()

            val contextWithNeither = PlaybookContext(
                calledToolNames = setOf("other"),
                artifacts = emptyList(),
                iterationCount = 1,
                blackboard = mockBlackboard(),
            )
            assertThat(condition.isSatisfied(contextWithNeither)).isFalse()
        }

        @Test
        fun `WhenPredicate condition should use custom logic`() {
            val condition = UnlockCondition.WhenPredicate { ctx ->
                ctx.iterationCount >= 3
            }

            val earlyContext = PlaybookContext(
                calledToolNames = emptySet(),
                artifacts = emptyList(),
                iterationCount = 2,
                blackboard = mockBlackboard(),
            )
            assertThat(condition.isSatisfied(earlyContext)).isFalse()

            val lateContext = PlaybookContext(
                calledToolNames = emptySet(),
                artifacts = emptyList(),
                iterationCount = 3,
                blackboard = mockBlackboard(),
            )
            assertThat(condition.isSatisfied(lateContext)).isTrue()
        }

        @Test
        fun `blackboard predicate condition should be satisfied when blackboard matches`() {
            val condition = UnlockCondition.WhenPredicate { ctx ->
                ctx.blackboard.objects.any { it is TestDocument }
            }

            val contextWithDoc = PlaybookContext(
                calledToolNames = emptySet(),
                artifacts = emptyList(),
                iterationCount = 0,
                blackboard = mockBlackboard(listOf(TestDocument("content"))),
            )
            assertThat(condition.isSatisfied(contextWithDoc)).isTrue()

            val contextWithoutDoc = PlaybookContext(
                calledToolNames = emptySet(),
                artifacts = emptyList(),
                iterationCount = 0,
                blackboard = mockBlackboard(),
            )
            assertThat(condition.isSatisfied(contextWithoutDoc)).isFalse()
        }
    }

    @Nested
    inner class PlaybookStateTests {

        @Test
        fun `should track tool calls`() {
            val state = PlaybookState(mockBlackboard())

            state.recordToolCall("search")
            state.recordToolCall("fetch")

            assertThat(state.calledToolNames).containsExactlyInAnyOrder("search", "fetch")
        }

        @Test
        fun `should track artifacts`() {
            val state = PlaybookState(mockBlackboard())
            val doc = TestDocument("content")

            state.recordArtifact(doc)

            assertThat(state.artifacts).containsExactly(doc)
        }

        @Test
        fun `should increment iteration count on tool calls`() {
            val state = PlaybookState(mockBlackboard())

            assertThat(state.iterationCount).isEqualTo(0)
            state.recordToolCall("search")
            assertThat(state.iterationCount).isEqualTo(1)
            state.recordToolCall("fetch")
            assertThat(state.iterationCount).isEqualTo(2)
        }

        @Test
        fun `should convert to PlaybookContext`() {
            val state = PlaybookState(mockBlackboard())
            state.recordToolCall("search")
            state.recordArtifact(TestDocument("content"))

            val context = state.toContext()

            assertThat(context.calledToolNames).containsExactly("search")
            assertThat(context.artifacts).hasSize(1)
            assertThat(context.iterationCount).isEqualTo(1)
        }
    }

    @Nested
    inner class StateTrackingToolTests {

        @Test
        fun `should delegate to wrapped tool`() {
            val delegate = createTestTool("search", "search result")
            val state = PlaybookState(mockBlackboard())
            val trackingTool = StateTrackingTool(delegate, state)

            val result = trackingTool.call("query")

            assertThat((result as Tool.Result.Text).content).isEqualTo("search result")
        }

        @Test
        fun `should record tool call in state`() {
            val delegate = createTestTool("search")
            val state = PlaybookState(mockBlackboard())
            val trackingTool = StateTrackingTool(delegate, state)

            trackingTool.call("query")

            assertThat(state.calledToolNames).contains("search")
        }

        @Test
        fun `should record artifacts in state`() {
            val doc = TestDocument("content")
            val delegate = createArtifactTool("search", doc)
            val state = PlaybookState(mockBlackboard())
            val trackingTool = StateTrackingTool(delegate, state)

            trackingTool.call("query")

            assertThat(state.artifacts).contains(doc)
        }

        @Test
        fun `should record iterable artifacts individually`() {
            val docs = listOf(TestDocument("doc1"), TestDocument("doc2"))
            val delegate = createArtifactTool("search", docs)
            val state = PlaybookState(mockBlackboard())
            val trackingTool = StateTrackingTool(delegate, state)

            trackingTool.call("query")

            assertThat(state.artifacts).hasSize(2)
        }
    }

    @Nested
    inner class ConditionalToolTests {

        @Test
        fun `should execute when condition satisfied`() {
            val delegate = createTestTool("analyze", "analysis result")
            val state = PlaybookState(mockBlackboard())
            state.recordToolCall("search")

            val condition = UnlockCondition.AfterTools("search")
            val conditionalTool = ConditionalTool(delegate, condition, state)

            val result = conditionalTool.call("input")

            assertThat((result as Tool.Result.Text).content).isEqualTo("analysis result")
        }

        @Test
        fun `should return locked message when condition not satisfied`() {
            val delegate = createTestTool("analyze")
            val state = PlaybookState(mockBlackboard())

            val condition = UnlockCondition.AfterTools("search")
            val conditionalTool = ConditionalTool(delegate, condition, state)

            val result = conditionalTool.call("input")

            assertThat((result as Tool.Result.Text).content)
                .contains("not yet available")
                .contains("search")
        }

        @Test
        fun `should add prerequisite note to description`() {
            val delegate = createTestTool("analyze")
            val state = PlaybookState(mockBlackboard())
            val condition = UnlockCondition.AfterTools("search")
            val conditionalTool = ConditionalTool(delegate, condition, state)

            assertThat(conditionalTool.definition.description)
                .contains("prerequisites")
        }

        @Test
        fun `should record call when executed`() {
            val delegate = createTestTool("analyze")
            val state = PlaybookState(mockBlackboard())
            state.recordToolCall("search")

            val condition = UnlockCondition.AfterTools("search")
            val conditionalTool = ConditionalTool(delegate, condition, state)

            conditionalTool.call("input")

            assertThat(state.calledToolNames).contains("analyze")
        }

        @Test
        fun `should not record call when locked`() {
            val delegate = createTestTool("analyze")
            val state = PlaybookState(mockBlackboard())

            val condition = UnlockCondition.AfterTools("search")
            val conditionalTool = ConditionalTool(delegate, condition, state)

            conditionalTool.call("input")

            assertThat(state.calledToolNames).doesNotContain("analyze")
        }
    }

    @Nested
    inner class ToolRegistrationTests {

        @Test
        fun `unlockedBy with artifact type should add locked tool`() {
            val searchTool = createTestTool("search")
            val analyzeTool = createTestTool("analyze")

            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool)
                .withTool(analyzeTool).unlockedByArtifact(TestDocument::class.java)

            assertThat(playbook.lockedToolCount).isEqualTo(1)
        }

        @Test
        fun `unlockedWhen with predicate should add locked tool`() {
            val searchTool = createTestTool("search")
            val analyzeTool = createTestTool("analyze")

            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool)
                .withTool(analyzeTool).unlockedWhen { it.iterationCount >= 2 }

            assertThat(playbook.lockedToolCount).isEqualTo(1)
        }

        @Test
        fun `unlockedWhen with UnlockCondition should add locked tool`() {
            val searchTool = createTestTool("search")
            val analyzeTool = createTestTool("analyze")
            val customCondition = UnlockCondition.AllOf(
                UnlockCondition.AfterTools("search"),
                UnlockCondition.WhenPredicate { it.iterationCount >= 1 }
            )

            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool)
                .withTool(analyzeTool).unlockedWhen(customCondition)

            assertThat(playbook.lockedToolCount).isEqualTo(1)
        }

        @Test
        fun `curried invoke with KClass should add locked tool`() {
            val searchTool = createTestTool("search")
            val analyzeTool = createTestTool("analyze")

            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool)
                .withTool(analyzeTool)(TestDocument::class)

            assertThat(playbook.lockedToolCount).isEqualTo(1)
        }

        @Test
        fun `unlockedByAll should require all prerequisites`() {
            val searchTool = createTestTool("search")
            val fetchTool = createTestTool("fetch")
            val analyzeTool = createTestTool("analyze")

            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool, fetchTool)
                .withTool(analyzeTool).unlockedByAll(searchTool, fetchTool)

            assertThat(playbook.lockedToolCount).isEqualTo(1)
        }

        @Test
        fun `unlockedByAny should accept any prerequisite`() {
            val searchTool = createTestTool("search")
            val fetchTool = createTestTool("fetch")
            val processTool = createTestTool("process")

            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool, fetchTool)
                .withTool(processTool).unlockedByAny(searchTool, fetchTool)

            assertThat(playbook.lockedToolCount).isEqualTo(1)
        }

        @Test
        fun `unlockedByArtifactMatching should add locked tool with predicate`() {
            val searchTool = createTestTool("search")
            val processTool = createTestTool("process")

            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool)
                .withTool(processTool).unlockedByArtifactMatching { it is TestDocument }

            assertThat(playbook.lockedToolCount).isEqualTo(1)
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `should return error when no tools configured`() {
            val playbook = PlaybookTool("test", "Test")

            val result = playbook.call("input")

            assertThat(result).isInstanceOf(Tool.Result.Error::class.java)
            assertThat((result as Tool.Result.Error).message).contains("No tools available")
        }

        @Test
        fun `should return error when no AgentProcess context`() {
            val searchTool = createTestTool("search")
            val playbook = PlaybookTool("test", "Test")
                .withTools(searchTool)

            val result = playbook.call("input")

            assertThat(result).isInstanceOf(Tool.Result.Error::class.java)
            assertThat((result as Tool.Result.Error).message).contains("No AgentProcess")
        }
    }
}
