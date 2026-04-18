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

import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.core.Budget
import com.embabel.agent.core.EarlyTerminationPolicy
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Usage
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.ai.model.PricingModel
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for parent/child AgentProcess cost and token aggregation.
 *
 * Strategy: the parent remains a REAL [SimpleAgentProcess] so its real `cost()` /
 * `usage()` / `modelsUsed()` / `costInfoString()` implementations are exercised.
 * Children are instances of [FixedCostAgentProcess], a test-only subclass that
 * overrides the aggregation methods to return fixed values. This decouples the
 * aggregation-under-test from the `LlmInvocation` → `PricingModel.costOf()` chain
 * (that chain is covered separately by `LlmInvocationHistoryTest`).
 *
 * Why this works end-to-end:
 *   - Before the fix, the default `LlmInvocationHistory.cost()` on the parent sums
 *     only its local `llmInvocations` and never calls `child.cost()` — so the stub
 *     override is irrelevant and the parent returns its own local cost (= 0 in
 *     these tests, since the parent has no recorded invocations). Tests fail.
 *   - After the fix, the parent's overridden `cost()` walks children via
 *     `platformServices.agentProcessRepository.findByParentId(id)` and calls
 *     `child.cost()` on each — which returns the stub's fixed value. Tests pass.
 *
 * The recursion is genuinely exercised at the parent level: the test does not
 * mock `findByParentId` or `PlatformServices`; it uses a real
 * `InMemoryAgentProcessRepository` (via `dummyPlatformServices()`) and real save().
 * Only the leaf value `child.cost()` is stubbed.
 *
 * Fixture:
 *   parent (real SimpleAgentProcess, own cost = 0)
 *     ├── child1 (FixedCostAgentProcess, fixedCost = 0.20, 300 prompt / 400 completion)
 *     └── child2 (FixedCostAgentProcess, fixedCost = 0.30, 500 prompt / 600 completion)
 *
 * Expected totals after fix: cost = 0.50, prompt = 800, completion = 1000,
 * totalTokens = 1800, models = [child1-llm, child2-llm].
 */
class AgentProcessCostAggregationTest {

    private lateinit var platformServices: PlatformServices
    private lateinit var parent: SimpleAgentProcess
    private lateinit var child1: FixedCostAgentProcess
    private lateinit var child2: FixedCostAgentProcess

    @BeforeEach
    fun setUp() {
        platformServices = dummyPlatformServices()

        parent = SimpleAgentProcess(
            id = "parent",
            parentId = null,
            agent = SimpleTestAgent,
            processOptions = ProcessOptions(),
            blackboard = InMemoryBlackboard(),
            platformServices = platformServices,
            plannerFactory = DefaultPlannerFactory,
        )
        child1 = FixedCostAgentProcess(
            id = "child-1",
            parentId = "parent",
            platformServices = platformServices,
            fixedCost = 0.20,
            fixedUsage = Usage(300, 400, null),
            fixedModels = listOf(mockLlmMetadata("child1-llm")),
        )
        child2 = FixedCostAgentProcess(
            id = "child-2",
            parentId = "parent",
            platformServices = platformServices,
            fixedCost = 0.30,
            fixedUsage = Usage(500, 600, null),
            fixedModels = listOf(mockLlmMetadata("child2-llm")),
        )

        // Save into the real in-memory repo so findByParentId can resolve them.
        platformServices.agentProcessRepository.save(parent)
        platformServices.agentProcessRepository.save(child1)
        platformServices.agentProcessRepository.save(child2)
    }

    @Nested
    inner class CostAggregation {

        @Test
        fun `parent cost equals sum of children fixed costs when parent has no own cost`() {
            // Before fix: 0.0 (only parent's own invocations, which are empty)
            // After fix:  0.50 (0 own + 0.20 + 0.30)
            assertEquals(
                0.50, parent.cost(), 1e-9,
                "BUG: parent.cost() must walk children and sum. " +
                        "Expected 0.50 (0 own + 0.20 child1 + 0.30 child2), got ${parent.cost()}."
            )
        }

        @Test
        fun `leaf stub child reports its fixed cost`() {
            // Baseline: a stub child returns its fixed value regardless of the bug.
            assertEquals(0.20, child1.cost(), 1e-9)
            assertEquals(0.30, child2.cost(), 1e-9)
        }

        @Test
        fun `parent with no children reports its own local cost only`() {
            // Baseline: a root process with no children. Must pass today and after the fix.
            val isolatedPs = dummyPlatformServices()
            val lonely = SimpleAgentProcess(
                id = "lonely", parentId = null,
                agent = SimpleTestAgent, processOptions = ProcessOptions(),
                blackboard = InMemoryBlackboard(), platformServices = isolatedPs,
                plannerFactory = DefaultPlannerFactory,
            )
            isolatedPs.agentProcessRepository.save(lonely)
            // Zero invocations => zero cost. After fix, still zero because no children.
            assertEquals(0.0, lonely.cost(), 1e-9)
        }

        @Test
        fun `three-level tree aggregates recursively through a real intermediate process`() {
            // parent (real) -> mid (real) -> leaf (stub).
            // Only the leaf is stubbed. `parent.cost()` and `mid.cost()` both use the
            // real (fixed) aggregation logic. This proves the fix walks more than one
            // level: if the fix only summed direct children, mid's 0.10 child would be
            // missed and parent.cost() would read only mid's own (= 0).
            val isolatedPs = dummyPlatformServices()
            val top = SimpleAgentProcess(
                id = "top", parentId = null,
                agent = SimpleTestAgent, processOptions = ProcessOptions(),
                blackboard = InMemoryBlackboard(), platformServices = isolatedPs,
                plannerFactory = DefaultPlannerFactory,
            )
            val mid = SimpleAgentProcess(
                id = "mid", parentId = "top",
                agent = SimpleTestAgent, processOptions = ProcessOptions(),
                blackboard = InMemoryBlackboard(), platformServices = isolatedPs,
                plannerFactory = DefaultPlannerFactory,
            )
            val leaf = FixedCostAgentProcess(
                id = "leaf", parentId = "mid",
                platformServices = isolatedPs,
                fixedCost = 0.10,
                fixedUsage = Usage(10, 20, null),
                fixedModels = listOf(mockLlmMetadata("leaf-llm")),
            )
            isolatedPs.agentProcessRepository.save(top)
            isolatedPs.agentProcessRepository.save(mid)
            isolatedPs.agentProcessRepository.save(leaf)

            // mid must itself correctly aggregate its direct child (leaf).
            // Testing this intermediate level isolates the bug: if `mid.cost()` is
            // already wrong (= 0), then `top.cost()` cannot be right by accident.
            assertEquals(
                0.10, mid.cost(), 1e-9,
                "BUG: intermediate level aggregation. mid has leaf as direct child " +
                        "with cost 0.10, expected mid.cost() = 0.10, got ${mid.cost()}."
            )
            assertEquals(
                0.10, top.cost(), 1e-9,
                "BUG: three-level aggregation. Expected 0.10 propagated from leaf, got ${top.cost()}."
            )
        }
    }

    @Nested
    inner class TokenUsageAggregation {

        @Test
        fun `parent prompt tokens equal sum of children`() {
            // Expected: 0 own + 300 + 500 = 800. Bug: 0.
            assertEquals(
                800, parent.usage().promptTokens,
                "BUG: parent.usage().promptTokens must include children. " +
                        "Expected 800, got ${parent.usage().promptTokens}."
            )
        }

        @Test
        fun `parent completion tokens equal sum of children`() {
            // Expected: 0 own + 400 + 600 = 1000. Bug: 0.
            assertEquals(
                1000, parent.usage().completionTokens,
                "BUG: parent.usage().completionTokens must include children. " +
                        "Expected 1000, got ${parent.usage().completionTokens}."
            )
        }

        @Test
        fun `parent totalTokens equal sum of children`() {
            // Expected: 800 + 1000 = 1800. Bug: 0.
            assertEquals(
                1800, parent.usage().totalTokens,
                "BUG: parent.usage().totalTokens must include children. " +
                        "Expected 1800, got ${parent.usage().totalTokens}."
            )
        }
    }

    @Nested
    inner class ModelsUsedAggregation {

        @Test
        fun `parent modelsUsed includes children models distinct by name sorted`() {
            // Expected: 2 child models, sorted. Bug: empty (parent has no own models).
            val names = parent.modelsUsed().map { it.name }
            assertEquals(
                listOf("child1-llm", "child2-llm"), names,
                "BUG: parent.modelsUsed() must include models used by children. " +
                        "Expected [child1-llm, child2-llm], got $names."
            )
        }
    }

    @Nested
    inner class CostInfoStringAggregation {

        @Test
        fun `costInfoString reflects total cost across children`() {
            val info = parent.costInfoString(verbose = false)
            // Expected after fix: cost = $0.5000. Bug: cost = $0.0000.
            assertTrue(
                info.contains("cost: \$0.5000"),
                "BUG: expected 'cost: \$0.5000' in costInfoString, got: $info"
            )
        }
    }

    @Nested
    inner class EarlyTerminationPolicyWithChildren {

        @Test
        fun `MaxCostEarlyTerminationPolicy must trigger when child costs push total over budget`() {
            // Budget $0.40. Parent alone = $0.00 (no own invocations), but children total = $0.50.
            // Policy MUST terminate. Current bug: returns null because it reads parent.cost() = 0.
            val policy = EarlyTerminationPolicy.hardBudgetLimit(0.40)
            val termination = policy.shouldTerminate(parent)
            assertNotNull(
                termination,
                "BUG: hardBudgetLimit(0.40) must enforce budget across child processes. " +
                        "Actual total child cost is \$0.50, parent own cost is \$0.00, " +
                        "budget is \$0.40. Policy should have terminated but returned null."
            )
        }

        @Test
        fun `MaxTokensEarlyTerminationPolicy must trigger when child tokens push total over limit`() {
            // Limit 1000. Parent own totalTokens = 0, children total = 1800. Policy MUST terminate.
            val policy = EarlyTerminationPolicy.maxTokens(1000)
            val termination = policy.shouldTerminate(parent)
            assertNotNull(
                termination,
                "BUG: maxTokens(1000) must enforce token limit across child processes. " +
                        "Actual total tokens is 1800, parent own tokens is 0, limit is 1000. " +
                        "Policy should have terminated but returned null."
            )
        }

        @Test
        fun `Budget default FirstOf policy must enforce cost limit across children`() {
            // Public API surface: the policy that gets wired by default into every ProcessOptions.
            val budget = Budget(cost = 0.40, actions = 1000, tokens = 10_000)
            val policy = budget.earlyTerminationPolicy()
            val termination = policy.shouldTerminate(parent)
            assertNotNull(
                termination,
                "BUG: Budget(cost=\$0.40).earlyTerminationPolicy() — the default policy wired " +
                        "into every ProcessOptions — must enforce cost limits across child processes. " +
                        "This is the concrete end-user impact: users who set a budget via " +
                        "ProcessOptions.withBudget(Budget(cost = X)) can see X silently exceeded."
            )
        }

        @Test
        fun `Budget default FirstOf policy must enforce token limit across children`() {
            val budget = Budget(cost = 100.0, actions = 1000, tokens = 1000)
            val policy = budget.earlyTerminationPolicy()
            val termination = policy.shouldTerminate(parent)
            assertNotNull(
                termination,
                "BUG: Budget(tokens=1000).earlyTerminationPolicy() must enforce token limits " +
                        "across child processes."
            )
        }
    }

    // ---- test helpers ------------------------------------------------------

    /**
     * A [SimpleAgentProcess] subclass that reports fixed cost/usage/models regardless
     * of its internal `llmInvocations` list. Used as a deterministic leaf stub for
     * aggregation tests: the parent's real aggregation logic calls `child.cost()`,
     * which returns the value set at construction. The rest of [SimpleAgentProcess]
     * behavior (construction, repository registration, id/parentId exposure) is
     * preserved so the parent's `findByParentId` + dispatch path is exercised for real.
     */
    private class FixedCostAgentProcess(
        id: String,
        parentId: String?,
        platformServices: PlatformServices,
        private val fixedCost: Double,
        private val fixedUsage: Usage = Usage(0, 0, null),
        private val fixedModels: List<LlmMetadata> = emptyList(),
    ) : SimpleAgentProcess(
        id = id,
        parentId = parentId,
        agent = SimpleTestAgent,
        processOptions = ProcessOptions(),
        blackboard = InMemoryBlackboard(),
        platformServices = platformServices,
        plannerFactory = DefaultPlannerFactory,
    ) {
        override fun cost(): Double = fixedCost
        override fun usage(): Usage = fixedUsage
        override fun modelsUsed(): List<LlmMetadata> = fixedModels
    }

    private fun mockLlmMetadata(modelName: String): LlmMetadata {
        val pricing = mockk<PricingModel>()
        every { pricing.costOf(any()) } returns 0.0
        val llm = mockk<LlmService<*>>()
        every { llm.name } returns modelName
        every { llm.pricingModel } returns pricing
        return llm
    }
}
