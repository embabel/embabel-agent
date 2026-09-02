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
package com.embabel.agent.spi.support.persistence

import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ContextId
import com.embabel.agent.core.Delay
import com.embabel.agent.core.ProcessControl
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProcessOptionsSnapshotTest {

    @Test
    fun `creates snapshot from process options`() {
        val options = processOptions()

        val snapshot = ProcessOptionsSnapshot.from(options)

        assertEquals("ctx-1", snapshot.contextId)
        assertEquals(options.verbosity, snapshot.verbosity)
        assertEquals(options.budget, snapshot.budget)
        assertEquals(Delay.MEDIUM, snapshot.toolDelay)
        assertEquals(Delay.LONG, snapshot.operationDelay)
        assertEquals(options.prune, snapshot.prune)
        assertEquals(options.ephemeral, snapshot.ephemeral)
        assertEquals(options.plannerType, snapshot.plannerType)
    }

    @Test
    fun `restores process options from snapshot`() {
        val restored = ProcessOptionsSnapshot.from(processOptions()).toProcessOptions()

        assertEquals(ContextId("ctx-1"), restored.contextId)
        assertEquals(Verbosity(showPrompts = true, showLlmResponses = true), restored.verbosity)
        assertEquals(Budget(cost = 1.5, actions = 12, tokens = 500), restored.budget)
        assertEquals(Delay.MEDIUM, restored.processControl.toolDelay)
        assertEquals(Delay.LONG, restored.processControl.operationDelay)
        assertEquals(PlannerType.GOAP, restored.plannerType)
        assertFalse(restored.ephemeral)
        assertNull(restored.blackboard)
        assertEquals(emptyList<Any>(), restored.listeners)
        assertEquals(ToolCallContext.EMPTY, restored.toolCallContext)
    }

    private fun processOptions(): ProcessOptions =
        ProcessOptions(
            contextId = ContextId("ctx-1"),
            verbosity = Verbosity(showPrompts = true, showLlmResponses = true),
            budget = Budget(cost = 1.5, actions = 12, tokens = 500),
            processControl = ProcessControl(
                toolDelay = Delay.MEDIUM,
                operationDelay = Delay.LONG,
            ),
            prune = true,
            ephemeral = false,
            plannerType = PlannerType.GOAP,
            toolCallContext = ToolCallContext.of("tenant" to "acme"),
        )
}
