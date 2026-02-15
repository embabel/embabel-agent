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
package com.embabel.agent.api.tool.agentic

import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.common.support.DelegatingStreamingPromptRunner
import com.embabel.agent.api.common.support.OperationContextDelegate
import com.embabel.agent.api.common.support.PromptExecutionDelegate
import com.embabel.agent.test.unit.FakePromptRunner
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolChainingTest {

    @Nested
    inner class ToolChainingInterface {

        @Test
        fun `withToolChainingFrom with class only should delegate to predicate overload`() {
            val chaining = object : ToolChaining<String> {
                var capturedType: Class<*>? = null
                var capturedPredicate: DomainToolPredicate<*>? = null

                override fun <T : Any> withToolChainingFrom(
                    type: Class<T>,
                    predicate: DomainToolPredicate<T>,
                ): String {
                    capturedType = type
                    capturedPredicate = predicate
                    return "result"
                }

                override fun withToolChainingFromAny(): String = "auto"
            }
            val result = chaining.withToolChainingFrom(TestUser::class.java)
            assertThat(result).isEqualTo("result")
            assertThat(chaining.capturedType).isEqualTo(TestUser::class.java)
            // The default predicate should accept everything
            @Suppress("UNCHECKED_CAST")
            val predicate = chaining.capturedPredicate as DomainToolPredicate<TestUser>
            assertThat(predicate.test(TestUser("1", "Alice"), null)).isTrue()
        }
    }

    @Nested
    inner class OperationContextDelegateToolChaining {

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
        fun `withToolChainingFrom should accumulate sources`() {
            val delegate = createDelegate()
            assertThat(delegate.domainToolSources).isEmpty()
            val updated = delegate.withToolChainingFrom(
                TestUser::class.java,
                DomainToolPredicate.always(),
            ) as OperationContextDelegate
            assertThat(updated.domainToolSources).hasSize(1)
            assertThat(updated.domainToolSources[0].type).isEqualTo(TestUser::class.java)
        }

        @Test
        fun `withToolChainingFrom should be additive`() {
            val delegate = createDelegate()
            val updated = delegate
                .withToolChainingFrom(TestUser::class.java, DomainToolPredicate.always())
                .withToolChainingFrom(TestOrder::class.java, DomainToolPredicate.always())
                    as OperationContextDelegate
            assertThat(updated.domainToolSources).hasSize(2)
        }

        @Test
        fun `withToolChainingFromAny should set autoDiscovery`() {
            val delegate = createDelegate()
            assertThat(delegate.autoDiscovery).isFalse()
            val updated = delegate.withToolChainingFromAny() as OperationContextDelegate
            assertThat(updated.autoDiscovery).isTrue()
        }

        @Test
        fun `domain tool state should coexist with other configuration`() {
            val delegate = createDelegate()
            val updated = delegate
                .withToolChainingFromAny()
                .withToolChainingFrom(TestUser::class.java, DomainToolPredicate.always())
                    as OperationContextDelegate
            assertThat(updated.autoDiscovery).isTrue()
            assertThat(updated.domainToolSources).hasSize(1)
        }
    }

    @Nested
    inner class DelegatingStreamingPromptRunnerToolChaining {

        private fun createRunner(): DelegatingStreamingPromptRunner {
            val context = mockk<com.embabel.agent.api.common.OperationContext>(relaxed = true)
            val delegate = OperationContextDelegate(
                context = context,
                llm = com.embabel.common.ai.model.LlmOptions(),
                toolGroups = emptySet(),
                toolObjects = emptyList(),
                promptContributors = emptyList(),
            )
            return DelegatingStreamingPromptRunner(delegate)
        }

        @Test
        fun `withToolChainingFrom should return PromptRunner`() {
            val runner = createRunner()
            val result: PromptRunner = runner.withToolChainingFrom(TestUser::class.java)
            assertThat(result).isInstanceOf(DelegatingStreamingPromptRunner::class.java)
        }

        @Test
        fun `withToolChainingFromAny should return PromptRunner`() {
            val runner = createRunner()
            val result: PromptRunner = runner.withToolChainingFromAny()
            assertThat(result).isInstanceOf(DelegatingStreamingPromptRunner::class.java)
        }

        @Test
        fun `withToolChainingFrom should propagate to delegate`() {
            val runner = createRunner()
            val updated = runner.withToolChainingFrom(
                TestUser::class.java,
                DomainToolPredicate.always(),
            ) as DelegatingStreamingPromptRunner
            val innerDelegate = updated.delegate as OperationContextDelegate
            assertThat(innerDelegate.domainToolSources).hasSize(1)
            assertThat(innerDelegate.domainToolSources[0].type).isEqualTo(TestUser::class.java)
        }

        @Test
        fun `withToolChainingFromAny should propagate to delegate`() {
            val runner = createRunner()
            val updated = runner.withToolChainingFromAny() as DelegatingStreamingPromptRunner
            val innerDelegate = updated.delegate as OperationContextDelegate
            assertThat(innerDelegate.autoDiscovery).isTrue()
        }
    }

    @Nested
    inner class FakePromptRunnerToolChaining {

        private fun createFakeRunner(): FakePromptRunner {
            val context = mockk<com.embabel.agent.api.common.OperationContext>(relaxed = true)
            every { context.agentPlatform().platformServices.llmOperations } returns mockk(relaxed = true)
            return FakePromptRunner(
                llm = com.embabel.common.ai.model.LlmOptions(),
                toolGroups = emptySet(),
                toolObjects = emptyList(),
                promptContributors = emptyList(),
                contextualPromptContributors = emptyList(),
                generateExamples = null,
                context = context,
            )
        }

        @Test
        fun `withToolChainingFrom should return same instance`() {
            val runner = createFakeRunner()
            val result = runner.withToolChainingFrom(TestUser::class.java)
            assertThat(result).isSameAs(runner)
        }

        @Test
        fun `withToolChainingFromAny should return same instance`() {
            val runner = createFakeRunner()
            val result = runner.withToolChainingFromAny()
            assertThat(result).isSameAs(runner)
        }
    }
}
