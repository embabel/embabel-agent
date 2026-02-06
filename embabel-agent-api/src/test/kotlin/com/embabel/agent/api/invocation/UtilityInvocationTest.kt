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
package com.embabel.agent.api.invocation

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.StuckHandler
import com.embabel.agent.api.common.StuckHandlerResult
import com.embabel.agent.api.common.StuckHandlingResultCode
import com.embabel.agent.api.common.scope.AgentScopeBuilder
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Tests for Issue #1334: UtilityInvocation doesn't preserve StuckHandler
 */
class UtilityInvocationTest {

    private val agentPlatform = mockk<AgentPlatform>(relaxed = true)

    // Test fixture: Agent that implements StuckHandler
    @Agent(description = "Test agent with stuck handler")
    class TestAgentWithStuckHandler : StuckHandler {
        var stuckCalled = false

        data class TestGoal(val result: String)

        @Action(description = "Test action")
        fun testAction(): TestGoal = TestGoal("done")

        override fun handleStuck(agentProcess: AgentProcess): StuckHandlerResult {
            stuckCalled = true
            return StuckHandlerResult(
                message = "Handled",
                handler = this,
                code = StuckHandlingResultCode.REPLAN,
                agentProcess = agentProcess,
            )
        }
    }

    // Test fixture: Agent without StuckHandler
    @Agent(description = "Test agent without stuck handler")
    class TestAgentWithoutStuckHandler {
        data class TestGoal(val result: String)

        @Action(description = "Test action")
        fun testAction(): TestGoal = TestGoal("done")
    }

    @Nested
    inner class AgentNaming {

        @Test
        fun `createPlatformAgent uses platform name by default`() {
            val agent = TestAgentWithoutStuckHandler()
            val scopeBuilder = AgentScopeBuilder.fromInstance(agent)

            every { agentPlatform.name } returns "my-platform"

            val invocation = UtilityInvocation(
                agentPlatform = agentPlatform,
                agentScopeBuilder = scopeBuilder,
            )

            val platformAgent = invocation.createPlatformAgent()

            kotlin.test.assertEquals("my-platform", platformAgent.name)
        }

        @Test
        fun `withAgentName overrides default platform name`() {
            val agent = TestAgentWithoutStuckHandler()
            val scopeBuilder = AgentScopeBuilder.fromInstance(agent)

            every { agentPlatform.name } returns "my-platform"

            val invocation = UtilityInvocation(
                agentPlatform = agentPlatform,
                agentScopeBuilder = scopeBuilder,
            ).withAgentName("custom-agent-name")

            val platformAgent = invocation.createPlatformAgent()

            kotlin.test.assertEquals("custom-agent-name", platformAgent.name)
        }

        @Test
        fun `withAgentName is immutable and returns new instance`() {
            val agent = TestAgentWithoutStuckHandler()
            val scopeBuilder = AgentScopeBuilder.fromInstance(agent)

            every { agentPlatform.name } returns "my-platform"

            val original = UtilityInvocation(
                agentPlatform = agentPlatform,
                agentScopeBuilder = scopeBuilder,
            )
            val modified = original.withAgentName("custom-name")

            kotlin.test.assertNotSame(original, modified)
            kotlin.test.assertEquals("my-platform", original.createPlatformAgent().name)
            kotlin.test.assertEquals("custom-name", modified.createPlatformAgent().name)
        }
    }

    @Nested
    inner class StuckHandlerPreservation {

        @Test
        fun `createPlatformAgent preserves stuckHandler from single agent scope`() {
            val agentWithHandler = TestAgentWithStuckHandler()
            val scopeBuilder = AgentScopeBuilder.fromInstance(agentWithHandler)

            every { agentPlatform.name } returns "test-platform"

            val invocation = UtilityInvocation(
                agentPlatform = agentPlatform,
                agentScopeBuilder = scopeBuilder,
            )

            val platformAgent = invocation.createPlatformAgent()

            assertNotNull(platformAgent.stuckHandler, "StuckHandler should be preserved from agent scope")
            assertSame(agentWithHandler, platformAgent.stuckHandler, "StuckHandler should be the original instance")
        }

        @Test
        fun `createPlatformAgent preserves stuckHandler when combining multiple agent scopes`() {
            val agentWithHandler = TestAgentWithStuckHandler()
            val agentWithoutHandler = TestAgentWithoutStuckHandler()
            val scopeBuilder = AgentScopeBuilder.fromInstances(agentWithHandler, agentWithoutHandler)

            every { agentPlatform.name } returns "test-platform"

            val invocation = UtilityInvocation(
                agentPlatform = agentPlatform,
                agentScopeBuilder = scopeBuilder,
            )

            val platformAgent = invocation.createPlatformAgent()

            assertNotNull(platformAgent.stuckHandler, "StuckHandler should be preserved when combining scopes")
            assertSame(agentWithHandler, platformAgent.stuckHandler, "StuckHandler should be the original instance")
        }

        @Test
        fun `createPlatformAgent has no stuckHandler when agent doesn't implement StuckHandler`() {
            val agentWithoutHandler = TestAgentWithoutStuckHandler()
            val scopeBuilder = AgentScopeBuilder.fromInstance(agentWithoutHandler)

            every { agentPlatform.name } returns "test-platform"

            val invocation = UtilityInvocation(
                agentPlatform = agentPlatform,
                agentScopeBuilder = scopeBuilder,
            )

            val platformAgent = invocation.createPlatformAgent()

            // This should pass - no stuckHandler when agent doesn't implement it
            kotlin.test.assertNull(platformAgent.stuckHandler, "StuckHandler should be null when not implemented")
        }
    }
}
