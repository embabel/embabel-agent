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
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.EarlyTerminationPolicy
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Usage
import com.embabel.agent.spi.config.spring.ProcessRepositoryProperties
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.InMemoryAgentProcessRepository
import com.embabel.agent.spi.support.SpringContextPlatformServices
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.ai.model.LlmMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Validates that subtree-aware cost aggregation (#368) stays correct when the
 * `InMemoryAgentProcessRepository` is under eviction pressure.
 *
 * `AbstractAgentProcess.cost()/usage()/modelsUsed()` walk children through
 * `agentProcessRepository.findByParentId(id)` — so an evicted child would silently
 * drop from the total. `HierarchyAwareEvictionPolicy` prevents that by only ever
 * evicting whole hierarchies, and only when every node is `finished`.
 *
 * These tests saturate `windowSize = 1` to force eviction attempts and assert
 * aggregation stays correct. The last test pins the known limitation: if a whole
 * finished hierarchy is legitimately evicted, a retained reference to its root no
 * longer sees the children's cost.
 */
class AgentProcessCostAggregationUnderEvictionTest {

    private fun servicesWithSingleRootWindow(): Pair<PlatformServices, InMemoryAgentProcessRepository> {
        val repo = InMemoryAgentProcessRepository(ProcessRepositoryProperties(windowSize = 1))
        val services = (dummyPlatformServices() as SpringContextPlatformServices).copy(
            agentProcessRepository = repo,
        )
        return services to repo
    }

    /** SimpleAgentProcess that exposes setStatus for deterministic test scenarios. */
    private open class StatusControllableProcess(
        id: String,
        parentId: String?,
        platformServices: PlatformServices,
    ) : SimpleAgentProcess(
        id = id,
        parentId = parentId,
        agent = SimpleTestAgent,
        processOptions = ProcessOptions(),
        blackboard = InMemoryBlackboard(),
        platformServices = platformServices,
        plannerFactory = DefaultPlannerFactory,
    ) {
        fun setTestStatus(status: AgentProcessStatusCode) = setStatus(status)
    }

    /** Leaf process returning fixed aggregation values (no recursion into children). */
    private class FixedCostLeaf(
        id: String,
        parentId: String?,
        platformServices: PlatformServices,
        private val fixedCost: Double,
        private val fixedUsage: Usage = Usage(0, 0, null),
        private val fixedModels: List<LlmMetadata> = emptyList(),
    ) : StatusControllableProcess(id, parentId, platformServices) {
        override fun cost(): Double = fixedCost
        override fun usage(): Usage = fixedUsage
        override fun modelsUsed(): List<LlmMetadata> = fixedModels
    }

    // ---- Invariants 1, 2, 3 — active hierarchy protected, aggregation correct --------------

    @Test
    fun `RUNNING parent hierarchy is never evicted and aggregation stays correct under repo pressure`() {
        val (services, repo) = servicesWithSingleRootWindow()

        val parent = StatusControllableProcess("parent", parentId = null, platformServices = services)
        val child1 = FixedCostLeaf("c1", "parent", services, 0.20, Usage(100, 200, null))
        val child2 = FixedCostLeaf("c2", "parent", services, 0.30, Usage(300, 400, null))
        // Parent RUNNING = not finished, children finished — hierarchy is NOT fully finished.
        parent.setTestStatus(AgentProcessStatusCode.RUNNING)
        child1.setTestStatus(AgentProcessStatusCode.COMPLETED)
        child2.setTestStatus(AgentProcessStatusCode.COMPLETED)

        repo.save(parent)
        repo.save(child1)
        repo.save(child2)

        // Saturate with other fully finished root hierarchies. FIFO eviction must stop at `parent`.
        repeat(5) { i ->
            val other = StatusControllableProcess("other-$i", parentId = null, platformServices = services)
            other.setTestStatus(AgentProcessStatusCode.COMPLETED)
            repo.save(other)
        }

        // Invariants 2 & 3 — subject hierarchy still in the repo.
        assertNotNull(repo.findById("parent"), "RUNNING parent must not be evicted")
        assertNotNull(repo.findById("c1"), "child of RUNNING parent must not be evicted")
        assertNotNull(repo.findById("c2"), "child of RUNNING parent must not be evicted")

        // Aggregation correct under pressure.
        assertEquals(
            0.50, parent.cost(), 1e-9,
            "parent.cost() must aggregate children (own 0 + c1 0.20 + c2 0.30) under eviction pressure",
        )
        assertEquals(400, parent.usage().promptTokens, "prompt tokens aggregated from children")
        assertEquals(600, parent.usage().completionTokens, "completion tokens aggregated from children")
    }

    @Test
    fun `FINISHED parent with non-finished child keeps hierarchy protected by invariant 2`() {
        val (services, repo) = servicesWithSingleRootWindow()

        val parent = StatusControllableProcess("p", parentId = null, platformServices = services)
        parent.setTestStatus(AgentProcessStatusCode.COMPLETED)
        val stillRunningChild = FixedCostLeaf("rc", "p", services, 0.40, Usage(50, 60, null))
        stillRunningChild.setTestStatus(AgentProcessStatusCode.RUNNING)

        repo.save(parent)
        repo.save(stillRunningChild)

        // Saturate with fully finished roots. Because stillRunningChild is RUNNING,
        // the entire hierarchy at `p` is still non-finished → eviction must not fire on it.
        repeat(3) { i ->
            val other = StatusControllableProcess("done-$i", parentId = null, platformServices = services)
            other.setTestStatus(AgentProcessStatusCode.COMPLETED)
            repo.save(other)
        }

        assertNotNull(repo.findById("p"), "parent with non-finished descendant must not be evicted")
        assertNotNull(repo.findById("rc"), "non-finished descendant must not be evicted")
        assertEquals(
            0.40, parent.cost(), 1e-9,
            "parent.cost() must still include the RUNNING child because eviction is blocked by invariant 2",
        )
    }

    @Test
    fun `EarlyTerminationPolicy MaxCost sees aggregated children cost correctly under eviction pressure`() {
        val (services, repo) = servicesWithSingleRootWindow()

        val parent = StatusControllableProcess("p", parentId = null, platformServices = services)
        parent.setTestStatus(AgentProcessStatusCode.RUNNING)
        val c1 = FixedCostLeaf("c1", "p", services, 0.30)
        val c2 = FixedCostLeaf("c2", "p", services, 0.25)
        c1.setTestStatus(AgentProcessStatusCode.COMPLETED)
        c2.setTestStatus(AgentProcessStatusCode.COMPLETED)
        repo.save(parent); repo.save(c1); repo.save(c2)

        // Pressure.
        repeat(5) { i ->
            val other = StatusControllableProcess("other-$i", parentId = null, platformServices = services)
            other.setTestStatus(AgentProcessStatusCode.COMPLETED)
            repo.save(other)
        }

        // Budget 0.40 < aggregated children total 0.55 → policy must fire.
        val result = EarlyTerminationPolicy.hardBudgetLimit(0.40).shouldTerminate(parent)
        assertNotNull(
            result,
            "EarlyTerminationPolicy.MaxCost must see children cost via findByParentId even " +
                    "when other hierarchies are under eviction pressure",
        )
    }

    // ---- Known limitation ------------------------------------------------------------------

    @Test
    fun `known limitation - fully finished hierarchy is evicted and retained reference sees incomplete cost`() {
        val (services, repo) = servicesWithSingleRootWindow()

        // Fully finished hierarchy: parent + 2 children all COMPLETED.
        val parent = StatusControllableProcess("p", parentId = null, platformServices = services)
        val c1 = FixedCostLeaf("c1", "p", services, 0.20)
        val c2 = FixedCostLeaf("c2", "p", services, 0.30)
        parent.setTestStatus(AgentProcessStatusCode.COMPLETED)
        c1.setTestStatus(AgentProcessStatusCode.COMPLETED)
        c2.setTestStatus(AgentProcessStatusCode.COMPLETED)
        repo.save(parent); repo.save(c1); repo.save(c2)

        // A new finished root triggers eviction of the oldest fully finished hierarchy.
        val other = StatusControllableProcess("other", parentId = null, platformServices = services)
        other.setTestStatus(AgentProcessStatusCode.COMPLETED)
        repo.save(other)

        // Hierarchy evicted from repo (root + both children).
        assertNull(repo.findById("p"), "fully finished hierarchy must be evicted")
        assertNull(repo.findById("c1"), "children are evicted with their parent")
        assertNull(repo.findById("c2"))

        // Retained Java reference still usable, but children are no longer reachable.
        // parent.cost() falls back to own cost (0) — the 0.50 from children is lost.
        assertEquals(
            0.0, parent.cost(), 1e-9,
            "Known limitation: once the whole hierarchy is evicted, cost() on a retained " +
                    "reference cannot find the children via findByParentId — aggregation is incomplete",
        )
    }
}
