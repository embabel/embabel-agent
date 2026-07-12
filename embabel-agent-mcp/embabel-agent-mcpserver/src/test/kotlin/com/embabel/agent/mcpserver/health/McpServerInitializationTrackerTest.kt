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
package com.embabel.agent.mcpserver.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpServerInitializationTrackerTest {

    @Test
    fun `starts as not started`() {
        val tracker = McpServerInitializationTracker()
        assertEquals(McpServerInitializationState.NOT_STARTED, tracker.state)
        assertTrue(tracker.issues.isEmpty())
    }

    @Test
    fun `markInitializing clears prior issues`() {
        val tracker = McpServerInitializationTracker()
        tracker.markFailed("old")
        tracker.markInitializing()

        assertEquals(McpServerInitializationState.INITIALIZING, tracker.state)
        assertTrue(tracker.issues.isEmpty())
    }

    @Test
    fun `markReady and markFailed update state`() {
        val tracker = McpServerInitializationTracker()
        tracker.markInitializing()
        tracker.markReady()
        assertEquals(McpServerInitializationState.READY, tracker.state)

        tracker.markInitializing()
        tracker.markFailed("pipeline failed")
        assertEquals(McpServerInitializationState.FAILED, tracker.state)
        assertEquals(listOf("pipeline failed"), tracker.issues)
    }
}
