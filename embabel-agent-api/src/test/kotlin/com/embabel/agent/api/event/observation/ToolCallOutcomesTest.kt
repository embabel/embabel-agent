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
@file:OptIn(InternalObservabilityApi::class)

package com.embabel.agent.api.event.observation

import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.api.event.ToolCallResponseEvent
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentProcessRunning
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Locks the Java-friendly view over the Kotlin [Result] inline value class: the two accessors must
 * map success/failure to plain nullable types that Java consumers (the observability module) can read.
 */
class ToolCallOutcomesTest {

    private fun event(result: Result<String>): ToolCallResponseEvent =
        ToolCallRequestEvent(
            agentProcess = dummyAgentProcessRunning(evenMoreEvilWizard()),
            action = null,
            tool = "someTool",
            toolGroupMetadata = null,
            toolInput = "{}",
            llmOptions = mockk(),
        ).responseEvent(result, Duration.ofMillis(1))

    @Test
    fun `success exposes the result text and no error`() {
        val event = event(Result.success("tool output"))
        assertSame("tool output", ToolCallOutcomes.resultText(event))
        assertNull(ToolCallOutcomes.error(event), "a successful call must have no error")
    }

    @Test
    fun `failure exposes the throwable and no result text`() {
        val boom = IllegalStateException("boom")
        val event = event(Result.failure(boom))
        assertNull(ToolCallOutcomes.resultText(event), "a failed call must have no result text")
        assertSame(boom, ToolCallOutcomes.error(event), "the original throwable must be exposed unchanged")
    }
}
