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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LoopMemoTest {

    private fun ctx(loopId: String?): ToolCallContext =
        if (loopId == null) ToolCallContext.EMPTY
        else ToolCallContext.of(mapOf(ToolCallContext.LOOP_ID_KEY to loopId))

    @Test
    fun `first call with a loop id returns true`() {
        val memo = LoopMemo()
        assertThat(memo.firstTimeIn(ctx("loop-1"))).isTrue()
    }

    @Test
    fun `subsequent calls with the same loop id return false`() {
        val memo = LoopMemo()
        memo.firstTimeIn(ctx("loop-1"))
        assertThat(memo.firstTimeIn(ctx("loop-1"))).isFalse()
        assertThat(memo.firstTimeIn(ctx("loop-1"))).isFalse()
    }

    @Test
    fun `different loop ids each register as first time`() {
        val memo = LoopMemo()
        assertThat(memo.firstTimeIn(ctx("loop-1"))).isTrue()
        assertThat(memo.firstTimeIn(ctx("loop-2"))).isTrue()
        assertThat(memo.firstTimeIn(ctx("loop-3"))).isTrue()
    }

    @Test
    fun `interleaved loop ids each maintain their own first-time flag`() {
        val memo = LoopMemo()
        assertThat(memo.firstTimeIn(ctx("a"))).isTrue()
        assertThat(memo.firstTimeIn(ctx("b"))).isTrue()
        assertThat(memo.firstTimeIn(ctx("a"))).isFalse()
        assertThat(memo.firstTimeIn(ctx("b"))).isFalse()
    }

    @Test
    fun `missing loop id falls back to always returning true`() {
        val memo = LoopMemo()
        // No loop id — caller didn't stamp the context. The documented
        // fallback is "always emit", so multiple calls should all be "first".
        assertThat(memo.firstTimeIn(ctx(null))).isTrue()
        assertThat(memo.firstTimeIn(ctx(null))).isTrue()
        assertThat(memo.firstTimeIn(ctx(null))).isTrue()
    }

    @Test
    fun `EMPTY context behaves like missing loop id`() {
        val memo = LoopMemo()
        assertThat(memo.firstTimeIn(ToolCallContext.EMPTY)).isTrue()
        assertThat(memo.firstTimeIn(ToolCallContext.EMPTY)).isTrue()
    }

    @Test
    fun `missing loop id does not pollute tracked set for subsequent real ids`() {
        val memo = LoopMemo()
        memo.firstTimeIn(ctx(null))
        memo.firstTimeIn(ctx(null))
        // A real loop id arriving later should still be "first time".
        assertThat(memo.firstTimeIn(ctx("loop-1"))).isTrue()
        assertThat(memo.firstTimeIn(ctx("loop-1"))).isFalse()
    }

    @Test
    fun `independent memo instances do not share state`() {
        val a = LoopMemo()
        val b = LoopMemo()
        assertThat(a.firstTimeIn(ctx("loop-1"))).isTrue()
        assertThat(b.firstTimeIn(ctx("loop-1"))).isTrue()
        assertThat(a.firstTimeIn(ctx("loop-1"))).isFalse()
        assertThat(b.firstTimeIn(ctx("loop-1"))).isFalse()
    }

    @Test
    fun `LRU eviction makes the oldest id look like first-time again`() {
        val memo = LoopMemo(maxTracked = 2)
        memo.firstTimeIn(ctx("a"))
        memo.firstTimeIn(ctx("b"))
        // c arrives — set was [a, b], adding c overflows so the oldest
        // entry (a) is evicted; set is now [b, c].
        assertThat(memo.firstTimeIn(ctx("c"))).isTrue()
        // c is the most recently added — still tracked.
        assertThat(memo.firstTimeIn(ctx("c"))).isFalse()
        // a was evicted — re-registers as first time.
        assertThat(memo.firstTimeIn(ctx("a"))).isTrue()
    }

    @Test
    fun `concurrent calls with the same id agree on a single first-timer`() {
        val memo = LoopMemo()
        val threadCount = 32
        val firstCount = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(1)
        try {
            val futures = (1..threadCount).map {
                executor.submit {
                    latch.await()
                    if (memo.firstTimeIn(ctx("shared"))) firstCount.incrementAndGet()
                }
            }
            latch.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertThat(firstCount.get()).isEqualTo(1)
    }

    @Test
    fun `default constructor uses DEFAULT_MAX_TRACKED`() {
        // Smoke test — just verify the no-arg constructor doesn't throw and
        // returns a usable memo. Capacity is documented at 1024.
        val memo = LoopMemo()
        assertThat(memo.firstTimeIn(ctx("x"))).isTrue()
        assertThat(memo.firstTimeIn(ctx("x"))).isFalse()
    }
}
