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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.common.support.DelegatingStreamingPromptRunner
import com.embabel.agent.api.common.support.OperationContextDelegate
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.agentic.DomainToolTracker
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolChainingInjectionStrategyTest {

    class ToolProvider {
        @LlmTool(description = "do something")
        fun doSomething(input: String): String = "done: $input"
    }

    class AnotherToolProvider {
        @LlmTool(description = "another action")
        fun anotherAction(): String = "another"
    }

    private fun createContext(): ToolInjectionContext = ToolInjectionContext(
        conversationHistory = emptyList(),
        currentTools = emptyList(),
        lastToolCall = ToolCallResult("test", "{}", "result", null),
        iterationCount = 0,
    )

    @Nested
    inner class DomainToolTrackerPendingTools {

        @Test
        fun `drainPendingTools returns empty when nothing discovered`() {
            val tracker = DomainToolTracker.withAutoDiscovery()
            assertThat(tracker.drainPendingTools()).isEmpty()
        }

        @Test
        fun `drainPendingTools returns discovered tools after auto-discovery bind`() {
            val tracker = DomainToolTracker.withAutoDiscovery()
            val tools = tracker.tryBindArtifact(ToolProvider())
            assertThat(tools).isNotEmpty()

            val pending = tracker.drainPendingTools()
            assertThat(pending).hasSize(tools.size)
            assertThat(pending.map { it.definition.name }).containsExactlyElementsOf(
                tools.map { it.definition.name }
            )
        }

        @Test
        fun `drainPendingTools clears buffer after drain`() {
            val tracker = DomainToolTracker.withAutoDiscovery()
            tracker.tryBindArtifact(ToolProvider())

            val first = tracker.drainPendingTools()
            assertThat(first).isNotEmpty()

            val second = tracker.drainPendingTools()
            assertThat(second).isEmpty()
        }

        @Test
        fun `drainPendingTools does not buffer tools from registered source mode`() {
            val tracker = DomainToolTracker(
                sources = listOf(
                    com.embabel.agent.api.tool.agentic.DomainToolSource(ToolProvider::class.java)
                ),
                autoDiscovery = false,
            )
            tracker.tryBindArtifact(ToolProvider())

            val pending = tracker.drainPendingTools()
            assertThat(pending).isEmpty()
        }

        @Test
        fun `drainPendingTools accumulates tools from successive discoveries`() {
            val tracker = DomainToolTracker.withAutoDiscovery()
            tracker.tryBindArtifact(ToolProvider())
            tracker.tryBindArtifact(AnotherToolProvider())

            val pending = tracker.drainPendingTools()
            // Auto-discovery clears previous bindings but pending buffer accumulates
            assertThat(pending).hasSizeGreaterThanOrEqualTo(2)
        }
    }

    @Nested
    inner class StrategyEvaluation {

        @Test
        fun `evaluate returns noChange when no pending tools`() {
            val tracker = DomainToolTracker.withAutoDiscovery()
            val strategy = ToolChainingInjectionStrategy(tracker)

            val result = strategy.evaluate(createContext())

            assertThat(result.hasChanges()).isFalse()
            assertThat(result.toolsToAdd).isEmpty()
            assertThat(result.toolsToRemove).isEmpty()
        }

        @Test
        fun `evaluate returns tools to add when pending`() {
            val tracker = DomainToolTracker.withAutoDiscovery()
            tracker.tryBindArtifact(ToolProvider())
            val strategy = ToolChainingInjectionStrategy(tracker)

            val result = strategy.evaluate(createContext())

            assertThat(result.hasChanges()).isTrue()
            assertThat(result.toolsToAdd).isNotEmpty()
            assertThat(result.toolsToAdd.map { it.definition.name }).contains("doSomething")
        }

        @Test
        fun `evaluate drains buffer so second call returns noChange`() {
            val tracker = DomainToolTracker.withAutoDiscovery()
            tracker.tryBindArtifact(ToolProvider())
            val strategy = ToolChainingInjectionStrategy(tracker)

            val first = strategy.evaluate(createContext())
            assertThat(first.hasChanges()).isTrue()

            val second = strategy.evaluate(createContext())
            assertThat(second.hasChanges()).isFalse()
        }
    }

    @Nested
    inner class DelegateChainInjectionStrategies {

        private fun createDelegate(): OperationContextDelegate {
            val context = mockk<com.embabel.agent.api.common.OperationContext>(relaxed = true)
            return OperationContextDelegate(
                context = context,
                llm = com.embabel.common.ai.model.LlmOptions(),
                toolGroups = emptySet(),
                toolObjects = emptyList(),
                promptContributors = emptyList(),
            )
        }

        @Test
        fun `withInjectionStrategies accumulates strategies on delegate`() {
            val delegate = createDelegate()
            assertThat(delegate.injectionStrategies).isEmpty()

            val strategy = mockk<ToolInjectionStrategy>()
            val updated = delegate.withInjectionStrategies(listOf(strategy)) as OperationContextDelegate
            assertThat(updated.injectionStrategies).hasSize(1)
        }

        @Test
        fun `withInjectionStrategies is additive`() {
            val delegate = createDelegate()
            val s1 = mockk<ToolInjectionStrategy>()
            val s2 = mockk<ToolInjectionStrategy>()

            val updated = delegate
                .withInjectionStrategies(listOf(s1))
                .withInjectionStrategies(listOf(s2)) as OperationContextDelegate
            assertThat(updated.injectionStrategies).hasSize(2)
        }

        @Test
        fun `DelegatingStreamingPromptRunner delegates withInjectionStrategies`() {
            val runner = DelegatingStreamingPromptRunner(createDelegate())
            val strategy = mockk<ToolInjectionStrategy>()

            val updated = runner.withInjectionStrategies(listOf(strategy))
            assertThat(updated).isInstanceOf(DelegatingStreamingPromptRunner::class.java)

            val innerDelegate = (updated as DelegatingStreamingPromptRunner).delegate as OperationContextDelegate
            assertThat(innerDelegate.injectionStrategies).hasSize(1)
        }
    }
}
