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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class OneShotPerLoopToolTest {

    /**
     * A test double that counts invocations and echoes the input back as text.
     */
    private class CountingTool(
        toolName: String = "skill_activator",
    ) : Tool {
        val callCount = AtomicInteger(0)
        override val definition: Tool.Definition = Tool.Definition(
            name = toolName,
            description = "test tool",
            inputSchema = Tool.InputSchema.of(),
        )

        override fun call(input: String): Tool.Result {
            callCount.incrementAndGet()
            return Tool.Result.text("body:$input")
        }
    }

    private fun ctx(loopId: String?): ToolCallContext =
        if (loopId == null) ToolCallContext.EMPTY
        else ToolCallContext.of(mapOf(ToolCallContext.LOOP_ID_KEY to loopId))

    @Test
    fun `first call delegates to underlying tool`() {
        val delegate = CountingTool()
        val gated = OneShotPerLoopTool(delegate, advice = "Stop calling.")

        val result = gated.call("{}", ctx("loop-1"))

        assertThat(delegate.callCount.get()).isEqualTo(1)
        assertThat((result as Tool.Result.Text).content).isEqualTo("body:{}")
    }

    @Test
    fun `second call within the same loop short-circuits with advice`() {
        val delegate = CountingTool()
        val gated = OneShotPerLoopTool(delegate, advice = "Write your script now.")

        gated.call("{}", ctx("loop-1"))
        val second = gated.call("{}", ctx("loop-1"))

        assertThat(delegate.callCount.get()).isEqualTo(1) // delegate not re-invoked
        val text = (second as Tool.Result.Text).content
        assertThat(text).contains("ALREADY LOADED")
        assertThat(text).contains("skill_activator")
        assertThat(text).contains("Write your script now.")
    }

    @Test
    fun `different loop ids each get one call`() {
        val delegate = CountingTool()
        val gated = OneShotPerLoopTool(delegate, advice = "x")

        gated.call("{}", ctx("loop-1"))
        gated.call("{}", ctx("loop-2"))
        gated.call("{}", ctx("loop-3"))

        assertThat(delegate.callCount.get()).isEqualTo(3)
    }

    @Test
    fun `repeated calls inside one loop only delegate once across many invocations`() {
        val delegate = CountingTool()
        val gated = OneShotPerLoopTool(delegate, advice = "x")

        repeat(10) { gated.call("{}", ctx("loop-1")) }

        assertThat(delegate.callCount.get()).isEqualTo(1)
    }

    @Test
    fun `different advice strings appear in the short-circuit message`() {
        val delegate = CountingTool()
        val gated = OneShotPerLoopTool(delegate, advice = "Answer the user.")

        gated.call("{}", ctx("loop-1"))
        val second = gated.call("{}", ctx("loop-1"))

        assertThat((second as Tool.Result.Text).content).contains("Answer the user.")
    }

    @Test
    fun `single-arg call routes through two-arg via DelegatingTool`() {
        val delegate = CountingTool()
        val gated = OneShotPerLoopTool(delegate, advice = "x")

        // Single-arg overload should reach the decorator's two-arg call
        // method (DelegatingTool's contract). With no loop id supplied,
        // LoopMemo's documented fallback is to treat every call as "first
        // time" — so without an orchestrator stamping a loop id, the
        // decorator degrades to a passthrough rather than silently locking.
        gated.call("{}")
        gated.call("{}")

        assertThat(delegate.callCount.get()).isEqualTo(2)
    }

    @Test
    fun `delegate is exposed for unwrapping`() {
        val delegate = CountingTool()
        val gated = OneShotPerLoopTool(delegate, advice = "x")

        assertThat(gated.delegate).isSameAs(delegate)
    }

    @Test
    fun `definition is forwarded from the delegate unchanged`() {
        val delegate = CountingTool(toolName = "my_skill")
        val gated = OneShotPerLoopTool(delegate, advice = "x")

        assertThat(gated.definition.name).isEqualTo("my_skill")
        assertThat(gated.definition.description).isEqualTo("test tool")
    }
}
