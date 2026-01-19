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
package com.embabel.agent.api.tool

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.spi.support.DelegatingTool
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for [ReplanningTool] and [ConditionalReplanningTool] wrappers.
 */
class ReplanningToolTest {

    private val mockAgentProcess = mockk<AgentProcess>()

    @BeforeEach
    fun setUp() {
        AgentProcess.set(mockAgentProcess)
    }

    @AfterEach
    fun tearDown() {
        AgentProcess.remove()
    }

    @Nested
    inner class BasicBehaviorTest {

        @Test
        fun `executes wrapped tool and throws ReplanRequestedException`() {
            val delegateTool = createMockTool("classifier") {
                Tool.Result.text("support_request")
            }
            val replanningTool = ReplanningTool(
                delegate = delegateTool,
                reason = "Classified user intent",
            )

            val exception = assertThrows<ReplanRequestedException> {
                replanningTool.call("{}")
            }

            assertEquals("Classified user intent", exception.reason)
        }

        @Test
        fun `default blackboard updater adds content to blackboard`() {
            val delegateTool = createMockTool("router") {
                Tool.Result.text("route_to_sales")
            }
            val replanningTool = ReplanningTool(
                delegate = delegateTool,
                reason = "Routing decision made",
            )

            val exception = assertThrows<ReplanRequestedException> {
                replanningTool.call("{}")
            }

            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            exception.blackboardUpdater(mockBlackboard)
            verify { mockBlackboard.addObject("route_to_sales") }
        }

        @Test
        fun `custom blackboard updater is called with content`() {
            val delegateTool = createMockTool("analyzer") {
                Tool.Result.text("""{"intent": "refund"}""")
            }
            var capturedContent: String? = null
            val replanningTool = ReplanningTool(
                delegate = delegateTool,
                reason = "Analysis complete",
                blackboardUpdater = { bb, content ->
                    capturedContent = content
                    bb.addObject(content)
                }
            )

            val exception = assertThrows<ReplanRequestedException> {
                replanningTool.call("{}")
            }

            // The blackboardUpdater captures content and passes it to the exception's callback
            // We need to invoke the exception's callback to trigger the captured lambda
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            exception.blackboardUpdater(mockBlackboard)
            assertEquals("""{"intent": "refund"}""", capturedContent)
        }
    }

    @Nested
    inner class ToolDefinitionTest {

        @Test
        fun `preserves tool definition from delegate`() {
            val delegateTool = createMockTool("my-tool") { Tool.Result.text("result") }
            val replanningTool = ReplanningTool(
                delegate = delegateTool,
                reason = "test",
            )

            assertEquals("my-tool", replanningTool.definition.name)
            assertEquals(delegateTool.definition.description, replanningTool.definition.description)
        }

        @Test
        fun `preserves tool metadata from delegate`() {
            val delegateTool = createMockTool("meta-tool") { Tool.Result.text("result") }
            val replanningTool = ReplanningTool(
                delegate = delegateTool,
                reason = "test",
            )

            assertEquals(delegateTool.metadata, replanningTool.metadata)
        }
    }

    @Nested
    inner class ResultTypesTest {

        @Test
        fun `handles Tool Result WithArtifact`() {
            val delegateTool = createMockToolWithArtifact("artifact-tool", "content", "artifact-data")
            val replanningTool = ReplanningTool(
                delegate = delegateTool,
                reason = "Artifact created",
            )

            val exception = assertThrows<ReplanRequestedException> {
                replanningTool.call("{}")
            }

            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            exception.blackboardUpdater(mockBlackboard)
            verify { mockBlackboard.addObject("content") }
        }

        @Test
        fun `handles Tool Result Error`() {
            val delegateTool = createMockTool("error-tool") {
                Tool.Result.error("Something went wrong")
            }
            val replanningTool = ReplanningTool(
                delegate = delegateTool,
                reason = "Error occurred, need different approach",
            )

            val exception = assertThrows<ReplanRequestedException> {
                replanningTool.call("{}")
            }

            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            exception.blackboardUpdater(mockBlackboard)
            verify { mockBlackboard.addObject("Something went wrong") }
        }
    }

    @Nested
    inner class DelegatingBehaviorTest {

        @Test
        fun `is a DelegatingTool`() {
            val delegateTool = createMockTool("test") { Tool.Result.text("result") }
            val replanningTool = ReplanningTool(
                delegate = delegateTool,
                reason = "test",
            )

            assertTrue(replanningTool is DelegatingTool)
            assertEquals(delegateTool, (replanningTool as DelegatingTool).delegate)
        }
    }

    @Nested
    inner class ConditionalReplanningToolTest {

        @Test
        fun `returns result normally when decider returns null`() {
            val delegateTool = createMockTool("checker") {
                Tool.Result.text("all_good")
            }
            val conditionalTool = ConditionalReplanningTool(
                delegate = delegateTool,
                decider = { null }
            )

            val result = conditionalTool.call("{}")

            assertEquals("all_good", (result as Tool.Result.Text).content)
        }

        @Test
        fun `throws ReplanRequestedException when decider returns decision`() {
            val delegateTool = createMockTool("classifier") {
                Tool.Result.text("needs_escalation")
            }
            val conditionalTool = ConditionalReplanningTool(
                delegate = delegateTool,
                decider = { context ->
                    if (context.resultContent == "needs_escalation") {
                        ReplanDecision(
                            reason = "Escalation required",
                            blackboardUpdater = { bb -> bb.addObject("escalated") }
                        )
                    } else null
                }
            )

            val exception = assertThrows<ReplanRequestedException> {
                conditionalTool.call("{}")
            }

            assertEquals("Escalation required", exception.reason)
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            exception.blackboardUpdater(mockBlackboard)
            verify { mockBlackboard.addObject("escalated") }
        }

        @Test
        fun `decider can access AgentProcess`() {
            every { mockAgentProcess.id } returns "process-456"

            val delegateTool = createMockTool("context-checker") {
                Tool.Result.text("check")
            }
            var capturedProcessId: String? = null
            val conditionalTool = ConditionalReplanningTool(
                delegate = delegateTool,
                decider = { context ->
                    capturedProcessId = context.agentProcess.id
                    ReplanDecision(reason = "Captured context")
                }
            )

            assertThrows<ReplanRequestedException> {
                conditionalTool.call("{}")
            }

            assertEquals("process-456", capturedProcessId)
        }

        @Test
        fun `decider can inspect result to make conditional decision`() {
            val delegateTool = createMockTool("scorer") {
                Tool.Result.text("95")
            }
            val conditionalTool = ConditionalReplanningTool(
                delegate = delegateTool,
                decider = { context ->
                    val score = context.resultContent.toIntOrNull() ?: 0
                    if (score > 90) {
                        ReplanDecision(
                            reason = "High score detected",
                            blackboardUpdater = { bb ->
                                bb.addObject(score)
                                bb.addObject("premium")
                            }
                        )
                    } else null
                }
            )

            val exception = assertThrows<ReplanRequestedException> {
                conditionalTool.call("{}")
            }

            assertEquals("High score detected", exception.reason)
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            exception.blackboardUpdater(mockBlackboard)
            verify { mockBlackboard.addObject(95) }
            verify { mockBlackboard.addObject("premium") }
        }

        @Test
        fun `decider can access tool definition and metadata`() {
            val delegateTool = createMockTool("my-special-tool") {
                Tool.Result.text("result")
            }
            var capturedToolName: String? = null
            val conditionalTool = ConditionalReplanningTool(
                delegate = delegateTool,
                decider = { context ->
                    capturedToolName = context.tool.definition.name
                    ReplanDecision(
                        reason = "Tool info captured",
                        blackboardUpdater = { bb -> bb.addObject(context.tool.definition.name) }
                    )
                }
            )

            val exception = assertThrows<ReplanRequestedException> {
                conditionalTool.call("{}")
            }

            assertEquals("my-special-tool", capturedToolName)
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            exception.blackboardUpdater(mockBlackboard)
            verify { mockBlackboard.addObject("my-special-tool") }
        }

        @Test
        fun `is a DelegatingTool`() {
            val delegateTool = createMockTool("test") { Tool.Result.text("result") }
            val conditionalTool = ConditionalReplanningTool(
                delegate = delegateTool,
                decider = { null }
            )

            assertTrue(conditionalTool is DelegatingTool)
            assertEquals(delegateTool, (conditionalTool as DelegatingTool).delegate)
        }

        @Test
        fun `decider can access artifact from WithArtifact result`() {
            data class RoutingDecision(val target: String, val confidence: Double)

            val routingResult = RoutingDecision("support", 0.95)
            val delegateTool = createMockToolWithArtifact(
                "router",
                "Routing to support",
                routingResult
            )

            var capturedArtifact: Any? = null
            val conditionalTool = ConditionalReplanningTool(
                delegate = delegateTool,
                decider = { context ->
                    capturedArtifact = context.artifact
                    ReplanDecision(
                        reason = "Routing decision made",
                        blackboardUpdater = { bb -> bb.addObject(context.artifact!!) }
                    )
                }
            )

            val exception = assertThrows<ReplanRequestedException> {
                conditionalTool.call("{}")
            }

            assertEquals(routingResult, capturedArtifact)
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            exception.blackboardUpdater(mockBlackboard)
            verify { mockBlackboard.addObject(routingResult) }
        }

        @Test
        fun `artifactAs returns typed artifact`() {
            data class Classification(val intent: String, val score: Double)

            val classification = Classification("refund", 0.87)
            val delegateTool = createMockToolWithArtifact(
                "classifier",
                "Intent: refund",
                classification
            )

            var capturedClassification: Classification? = null
            val conditionalTool = ConditionalReplanningTool(
                delegate = delegateTool,
                decider = { context ->
                    capturedClassification = context.artifactAs<Classification>()
                    if (capturedClassification != null && capturedClassification!!.score > 0.8) {
                        ReplanDecision(
                            reason = "High confidence classification",
                            blackboardUpdater = { bb -> bb.addObject(capturedClassification!!.intent) }
                        )
                    } else null
                }
            )

            val exception = assertThrows<ReplanRequestedException> {
                conditionalTool.call("{}")
            }

            assertEquals(classification, capturedClassification)
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            exception.blackboardUpdater(mockBlackboard)
            verify { mockBlackboard.addObject("refund") }
        }

        @Test
        fun `artifactAs returns null for wrong type`() {
            data class WrongType(val x: Int)

            val delegateTool = createMockToolWithArtifact(
                "tool",
                "content",
                "string artifact"
            )

            var wrongTypeResult: WrongType? = "marker" as? WrongType  // ensure it's null from wrong cast
            val conditionalTool = ConditionalReplanningTool(
                delegate = delegateTool,
                decider = { context ->
                    wrongTypeResult = context.artifactAs<WrongType>()
                    null  // continue normally
                }
            )

            conditionalTool.call("{}")

            assertNull(wrongTypeResult)
        }

        @Test
        fun `artifact is null for Text result`() {
            val delegateTool = createMockTool("text-tool") {
                Tool.Result.text("just text")
            }

            var capturedArtifact: Any? = "marker"  // non-null marker
            val conditionalTool = ConditionalReplanningTool(
                delegate = delegateTool,
                decider = { context ->
                    capturedArtifact = context.artifact
                    null
                }
            )

            conditionalTool.call("{}")

            assertNull(capturedArtifact)
        }

        @Test
        fun `resultContent works with full result`() {
            val delegateTool = createMockToolWithArtifact(
                "artifact-tool",
                "the content string",
                mapOf("key" to "value")
            )

            var capturedContent: String? = null
            val conditionalTool = ConditionalReplanningTool(
                delegate = delegateTool,
                decider = { context ->
                    capturedContent = context.resultContent
                    null
                }
            )

            conditionalTool.call("{}")

            assertEquals("the content string", capturedContent)
        }
    }

    private fun createMockTool(name: String, onCall: (String) -> Tool.Result): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = name,
            description = "Mock tool $name",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result = onCall(input)
    }

    private fun createMockToolWithArtifact(name: String, content: String, artifact: Any): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = name,
            description = "Mock tool $name",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result = Tool.Result.WithArtifact(content, artifact)
    }
}
