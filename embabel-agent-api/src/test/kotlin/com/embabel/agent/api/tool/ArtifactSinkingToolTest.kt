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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ArtifactSinkingToolTest {

    @Nested
    inner class CoreFunctionality {

        @Test
        fun `delegates call to underlying tool and returns result`() {
            val sink = ListSink()
            val delegateTool = createMockTool("test-tool") {
                Tool.Result.WithArtifact("result content", "artifact")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            val result = capturingTool.call("{}")

            assertTrue(result is Tool.Result.WithArtifact)
            assertEquals("result content", (result as Tool.Result.WithArtifact).content)
        }

        @Test
        fun `preserves tool definition from delegate`() {
            val sink = ListSink()
            val delegateTool = createMockTool("preserved-name") { Tool.Result.text("result") }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            assertEquals("preserved-name", capturingTool.definition.name)
            assertEquals(delegateTool.definition.description, capturingTool.definition.description)
        }

        @Test
        fun `preserves tool metadata from delegate`() {
            val sink = ListSink()
            val delegateTool = createMockTool("meta-tool") { Tool.Result.text("result") }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            assertEquals(delegateTool.metadata, capturingTool.metadata)
        }

        @Test
        fun `propagates exception from delegate tool`() {
            val sink = ListSink()
            val delegateTool = createMockTool("throwing-tool") {
                throw RuntimeException("Tool failed")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            val exception = assertThrows<RuntimeException> {
                capturingTool.call("{}")
            }

            assertEquals("Tool failed", exception.message)
            assertTrue(sink.items().isEmpty())
        }
    }

    @Nested
    inner class SingleArtifactCapture {

        @Test
        fun `captures matching artifact`() {
            val sink = ListSink()
            val delegateTool = createMockTool("capture-tool") {
                Tool.Result.WithArtifact("content", "captured")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            capturingTool.call("{}")

            assertEquals(listOf("captured"), sink.items())
        }

        @Test
        fun `does not capture non-matching type`() {
            val sink = ListSink()
            val delegateTool = createMockTool("type-mismatch-tool") {
                Tool.Result.WithArtifact("content", 12345)
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            capturingTool.call("{}")

            assertTrue(sink.items().isEmpty())
        }

        @Test
        fun `does not capture when result is Text`() {
            val sink = ListSink()
            val delegateTool = createMockTool("text-tool") {
                Tool.Result.text("just text")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            capturingTool.call("{}")

            assertTrue(sink.items().isEmpty())
        }

        @Test
        fun `does not capture when result is Error`() {
            val sink = ListSink()
            val delegateTool = createMockTool("error-tool") {
                Tool.Result.error("error message")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            capturingTool.call("{}")

            assertTrue(sink.items().isEmpty())
        }
    }

    @Nested
    inner class Filtering {

        @Test
        fun `captures when filter returns true`() {
            val sink = ListSink()
            val delegateTool = createMockTool("filter-pass-tool") {
                Tool.Result.WithArtifact("content", "accepted")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
                filter = { it.startsWith("acc") },
            )

            capturingTool.call("{}")

            assertEquals(listOf("accepted"), sink.items())
        }

        @Test
        fun `does not capture when filter returns false`() {
            val sink = ListSink()
            val delegateTool = createMockTool("filter-reject-tool") {
                Tool.Result.WithArtifact("content", "rejected")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
                filter = { it.startsWith("acc") },
            )

            capturingTool.call("{}")

            assertTrue(sink.items().isEmpty())
        }

        @Test
        fun `filter receives typed artifact`() {
            val sink = ListSink()
            var captured: String? = null
            val delegateTool = createMockTool("capture-tool") {
                Tool.Result.WithArtifact("content", "test value")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
                filter = { captured = it; true },
            )

            capturingTool.call("{}")

            assertEquals("test value", captured)
        }
    }

    @Nested
    inner class Transformation {

        @Test
        fun `transforms artifact before sending to sink`() {
            val sink = ListSink()
            val delegateTool = createMockTool("transform-tool") {
                Tool.Result.WithArtifact("content", "lowercase")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
                transform = { it.uppercase() },
            )

            capturingTool.call("{}")

            assertEquals(listOf("LOWERCASE"), sink.items())
        }

        @Test
        fun `transform can change type`() {
            val sink = ListSink()
            val delegateTool = createMockTool("type-change-tool") {
                Tool.Result.WithArtifact("content", "hello")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
                transform = { it.length },
            )

            capturingTool.call("{}")

            assertEquals(listOf(5), sink.items())
        }

        @Test
        fun `transform occurs after filtering`() {
            val sink = ListSink()
            val order = mutableListOf<String>()
            val delegateTool = createMockTool("order-tool") {
                Tool.Result.WithArtifact("content", "test")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
                filter = { order.add("filter"); true },
                transform = { order.add("transform"); it },
            )

            capturingTool.call("{}")

            assertEquals(listOf("filter", "transform"), order)
        }

        @Test
        fun `transform not called when filter rejects`() {
            val sink = ListSink()
            var transformCalled = false
            val delegateTool = createMockTool("no-transform-tool") {
                Tool.Result.WithArtifact("content", "rejected")
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
                filter = { false },
                transform = { transformCalled = true; it },
            )

            capturingTool.call("{}")

            assertEquals(false, transformCalled)
        }
    }

    @Nested
    inner class IterableHandling {

        @Test
        fun `unwraps iterable and captures all matching items`() {
            val sink = ListSink()
            val delegateTool = createMockTool("iterable-tool") {
                Tool.Result.WithArtifact("content", listOf("one", "two", "three"))
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            capturingTool.call("{}")

            assertEquals(listOf("one", "two", "three"), sink.items())
        }

        @Test
        fun `filters each item in iterable`() {
            val sink = ListSink()
            val delegateTool = createMockTool("filter-iterable-tool") {
                Tool.Result.WithArtifact("content", listOf("accept1", "reject", "accept2"))
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
                filter = { it.startsWith("accept") },
            )

            capturingTool.call("{}")

            assertEquals(listOf("accept1", "accept2"), sink.items())
        }

        @Test
        fun `transforms each item in iterable`() {
            val sink = ListSink()
            val delegateTool = createMockTool("transform-iterable-tool") {
                Tool.Result.WithArtifact("content", listOf("a", "b", "c"))
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
                transform = { it.uppercase() },
            )

            capturingTool.call("{}")

            assertEquals(listOf("A", "B", "C"), sink.items())
        }

        @Test
        fun `filters non-matching types from mixed iterable`() {
            val sink = ListSink()
            val delegateTool = createMockTool("mixed-iterable-tool") {
                Tool.Result.WithArtifact("content", listOf("string", 123, "another", 456))
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            capturingTool.call("{}")

            assertEquals(listOf("string", "another"), sink.items())
        }

        @Test
        fun `handles empty iterable`() {
            val sink = ListSink()
            val delegateTool = createMockTool("empty-iterable-tool") {
                Tool.Result.WithArtifact("content", emptyList<String>())
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            capturingTool.call("{}")

            assertTrue(sink.items().isEmpty())
        }

        @Test
        fun `handles Set as iterable`() {
            val sink = ListSink()
            val delegateTool = createMockTool("set-tool") {
                Tool.Result.WithArtifact("content", setOf("a", "b", "c"))
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = String::class.java,
                sink = sink,
            )

            capturingTool.call("{}")

            assertEquals(3, sink.items().size)
            assertTrue(sink.items().containsAll(listOf("a", "b", "c")))
        }
    }

    @Nested
    inner class SubtypeHandling {

        @Test
        fun `accepts subtypes when filtering by supertype`() {
            val sink = ListSink()
            val dog = TestDog("Buddy")
            val delegateTool = createMockTool("subtype-tool") {
                Tool.Result.WithArtifact("content", dog)
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = TestAnimal::class.java,
                sink = sink,
            )

            capturingTool.call("{}")

            assertEquals(listOf(dog), sink.items())
        }

        @Test
        fun `filter receives correct subtype`() {
            val sink = ListSink()
            var captured: TestAnimal? = null
            val dog = TestDog("Buddy")
            val delegateTool = createMockTool("capture-subtype-tool") {
                Tool.Result.WithArtifact("content", dog)
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = TestAnimal::class.java,
                sink = sink,
                filter = { captured = it; true },
            )

            capturingTool.call("{}")

            assertTrue(captured is TestDog)
            assertEquals("Buddy", (captured as TestDog).name)
        }

        @Test
        fun `filters mixed subtypes in iterable`() {
            val sink = ListSink()
            val buddy = TestDog("Buddy")
            val max = TestDog("Max")
            val animals = listOf(buddy, TestCat("Whiskers"), max)
            val delegateTool = createMockTool("mixed-subtypes-tool") {
                Tool.Result.WithArtifact("content", animals)
            }
            val capturingTool = ArtifactSinkingTool(
                delegate = delegateTool,
                clazz = TestDog::class.java,
                sink = sink,
            )

            capturingTool.call("{}")

            assertEquals(listOf(buddy, max), sink.items())
        }
    }

    @Nested
    inner class ListSinkTest {

        @Test
        fun `collects all accepted artifacts`() {
            val sink = ListSink()
            sink.accept("first")
            sink.accept("second")
            sink.accept(123)

            assertEquals(listOf("first", "second", 123), sink.items())
        }

        @Test
        fun `items returns immutable copy`() {
            val sink = ListSink()
            sink.accept("item")
            val items = sink.items()

            sink.accept("another")

            assertEquals(1, items.size)
            assertEquals(2, sink.items().size)
        }

        @Test
        fun `can use provided list`() {
            val target = mutableListOf<Any>()
            val sink = ListSink(target)
            sink.accept("item")

            assertEquals(listOf("item"), target)
        }
    }

    @Nested
    inner class CompositeSinkTest {

        @Test
        fun `delegates to all sinks`() {
            val sink1 = ListSink()
            val sink2 = ListSink()
            val composite = CompositeSink(sink1, sink2)

            composite.accept("shared")

            assertEquals(listOf("shared"), sink1.items())
            assertEquals(listOf("shared"), sink2.items())
        }

        @Test
        fun `works with empty sink list`() {
            val composite = CompositeSink()
            composite.accept("ignored") // Should not throw
        }

        @Test
        fun `accepts list constructor`() {
            val sinks = listOf(ListSink(), ListSink())
            val composite = CompositeSink(sinks)

            composite.accept("test")

            sinks.forEach { assertEquals(listOf("test"), (it as ListSink).items()) }
        }
    }

    @Nested
    inner class BlackboardSinkTest {

        private lateinit var mockAgentProcess: AgentProcess
        private lateinit var mockBlackboard: Blackboard

        @BeforeEach
        fun setUp() {
            mockBlackboard = mockk(relaxed = true)
            mockAgentProcess = mockk(relaxed = true)
            every { mockAgentProcess.blackboard } returns mockBlackboard
            AgentProcess.set(mockAgentProcess)
        }

        @AfterEach
        fun tearDown() {
            AgentProcess.remove()
        }

        @Test
        fun `publishes to blackboard`() {
            BlackboardSink.accept("artifact")

            verify { mockBlackboard.addObject("artifact") }
        }

        @Test
        fun `throws when no AgentProcess`() {
            AgentProcess.remove()

            val exception = assertThrows<IllegalStateException> {
                BlackboardSink.accept("artifact")
            }

            assertTrue(exception.message?.contains("No AgentProcess") == true)
        }

        @Test
        fun `uses blackboard from current AgentProcess`() {
            val otherBlackboard = mockk<Blackboard>(relaxed = true)
            val otherProcess = mockk<AgentProcess>(relaxed = true)
            every { otherProcess.blackboard } returns otherBlackboard
            AgentProcess.set(otherProcess)

            BlackboardSink.accept("artifact")

            verify { otherBlackboard.addObject("artifact") }
            verify(exactly = 0) { mockBlackboard.addObject(any()) }
        }
    }

    @Nested
    inner class StaticFactoryMethods {

        @BeforeEach
        fun setUp() {
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            val mockAgentProcess = mockk<AgentProcess>(relaxed = true)
            every { mockAgentProcess.blackboard } returns mockBlackboard
            AgentProcess.set(mockAgentProcess)
        }

        @AfterEach
        fun tearDown() {
            AgentProcess.remove()
        }

        @Test
        fun `sinkArtifacts works with sink`() {
            val sink = ListSink()
            val tool = createMockTool("static-tool") {
                Tool.Result.WithArtifact("content", "captured")
            }

            val capturing = Tool.sinkArtifacts(tool, String::class.java, sink)
            capturing.call("{}")

            assertEquals(listOf("captured"), sink.items())
        }

        @Test
        fun `sinkArtifacts with filter and transform`() {
            val sink = ListSink()
            val tool = createMockTool("static-tool") {
                Tool.Result.WithArtifact("content", "hello")
            }

            val capturing = Tool.sinkArtifacts(
                tool,
                String::class.java,
                sink,
                { it.length > 3 },
                { it.uppercase() },
            )
            capturing.call("{}")

            assertEquals(listOf("HELLO"), sink.items())
        }

        @Test
        fun `publishToBlackboard without type param`() {
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            val mockAgentProcess = mockk<AgentProcess>(relaxed = true)
            every { mockAgentProcess.blackboard } returns mockBlackboard
            AgentProcess.set(mockAgentProcess)

            val tool = createMockTool("bb-tool") {
                Tool.Result.WithArtifact("content", "published")
            }

            Tool.publishToBlackboard(tool).call("{}")

            verify { mockBlackboard.addObject("published") }
        }

        @Test
        fun `publishToBlackboard with type param`() {
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            val mockAgentProcess = mockk<AgentProcess>(relaxed = true)
            every { mockAgentProcess.blackboard } returns mockBlackboard
            AgentProcess.set(mockAgentProcess)

            val tool = createMockTool("typed-bb-tool") {
                Tool.Result.WithArtifact("content", "published")
            }

            Tool.publishToBlackboard(tool, String::class.java).call("{}")

            verify { mockBlackboard.addObject("published") }
        }

        @Test
        fun `publishToBlackboard with filter and transform`() {
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            val mockAgentProcess = mockk<AgentProcess>(relaxed = true)
            every { mockAgentProcess.blackboard } returns mockBlackboard
            AgentProcess.set(mockAgentProcess)

            val tool = createMockTool("filtered-bb-tool") {
                Tool.Result.WithArtifact("content", listOf("yes", "no", "yes2"))
            }

            Tool.publishToBlackboard(
                tool,
                String::class.java,
                { it.startsWith("yes") },
                { it },
            ).call("{}")

            verify { mockBlackboard.addObject("yes") }
            verify { mockBlackboard.addObject("yes2") }
            verify(exactly = 0) { mockBlackboard.addObject("no") }
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
}

// Test classes for subtype handling - defined at file level
open class TestAnimal(val name: String)
class TestDog(name: String) : TestAnimal(name)
class TestCat(name: String) : TestAnimal(name)
