/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.common.autonomy

import com.embabel.agent.api.common.autonomy.AgentInvocation.Companion.builder
import com.embabel.agent.api.common.autonomy.AgentInvocation.Companion.create
import com.embabel.agent.core.*
import com.embabel.agent.core.Goal.Companion.createInstance
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

class AgentInvocationKotlinTest {

    private val agentPlatform = mockk<AgentPlatform>()

    private val agent = mockk<Agent>()

    private val agentProcess = mockk<AgentProcess>()

    private val goal: Goal = createInstance("Test goal", Bar::class.java)

    @Test
    fun `default varargs invocation`() {
        val foo = Foo()
        val expected = Bar()
        val invocation: AgentInvocation<Bar> = create(agentPlatform)

        every {agentPlatform.agents()} returns listOf(agent)
        every {agent.goals} returns setOf(goal)
        every {agentPlatform.createAgentProcessFrom(
            agent = agent,
            processOptions = any(),
            *arrayOf(foo)
        )} returns agentProcess
        every {agentPlatform.start(agentProcess)} returns CompletableFuture.completedFuture(agentProcess)
        every {agentProcess.last(Bar::class.java)} returns expected

        val bar: Bar = invocation.invoke(foo)
        assertEquals(
            expected = expected,
            actual = bar
        )
    }

    @Test
    fun `default map invocation`() {
        val foo = Foo()
        val map = mapOf("id" to foo)
        val expected = Bar()
        val invocation: AgentInvocation<Bar> = create(agentPlatform)

        every {agentPlatform.agents()} returns listOf(agent)
        every {agent.goals} returns setOf(goal)
        every {agentPlatform.createAgentProcess(
            agent = agent,
            processOptions = any(),
            bindings = map
        )} returns agentProcess
        every {agentPlatform.start(agentProcess)} returns CompletableFuture.completedFuture(agentProcess)
        every {agentProcess.last(Bar::class.java)} returns expected

        val bar: Bar = invocation.invoke(map)
        assertEquals(
            expected = expected,
            actual = bar
        )
    }

    @Test
    fun `custom processing options`() {
        val processOptions = ProcessOptions(verbosity = Verbosity(debug = true))
        val invocation: AgentInvocation<Bar> = builder(agentPlatform)
            .options(processOptions)
            .build()

        every {agentPlatform.agents()} returns listOf(agent)
        every {agent.goals} returns setOf(goal)
        every {agentPlatform.createAgentProcessFrom(any(),
            processOptions = processOptions,
            any()
        )} returns agentProcess
        every {agentPlatform.start(agentProcess)} returns CompletableFuture.completedFuture(agentProcess)
        every {agentProcess.last(Bar::class.java)} returns Bar()


        invocation.invoke(Foo())
    }

}

class Foo
class Bar
