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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(InternalAgentStateApi::class)
class InMemoryBlackboardStateApiTest {

    @Test
    fun `exposes bindings and entry order`() {
        val blackboard = InMemoryBlackboard("bb-1")
        val first = SampleValue("first")
        val second = SampleValue("second")

        blackboard["named"] = first
        blackboard.addObject(second)

        val state = blackboard.internalState()

        assertEquals("bb-1", state.blackboardId)
        assertEquals(mapOf("named" to first), state.bindings)
        assertEquals(listOf(first, second), state.entries)
    }

    @Test
    fun `exposes hidden entries`() {
        val blackboard = InMemoryBlackboard("bb-1")
        val visible = SampleValue("visible")
        val hidden = SampleValue("hidden")

        blackboard.addObject(visible)
        blackboard.addObject(hidden)
        blackboard.hide(hidden)

        val state = blackboard.internalState()

        assertEquals(listOf(visible, hidden), state.entries)
        assertEquals(setOf(hidden), state.hiddenEntries)
        assertEquals(listOf(visible), blackboard.objects)
    }

    @Test
    fun `exposes protected keys`() {
        val blackboard = InMemoryBlackboard("bb-1")
        val value = SampleValue("protected")

        blackboard.bindProtected("user", value)

        val state = blackboard.internalState()

        assertEquals(mapOf("user" to value), state.bindings)
        assertEquals(setOf("user"), state.protectedKeys)
        assertTrue(state.entries.contains(value))
    }

    @Test
    fun `replaces internal state`() {
        val original = InMemoryBlackboard("bb-1")
        val named = SampleValue("named")
        val entry = SampleValue("entry")

        original["named"] = named
        original.addObject(entry)

        val restored = InMemoryBlackboard("bb-1")
        restored.replaceInternalState(original.internalState())

        assertEquals(named, restored["named"])
        assertEquals(listOf(named, entry), restored.objects)
    }

    @Test
    fun `replaces hidden entries and protected keys`() {
        val original = InMemoryBlackboard("bb-1")
        val visible = SampleValue("visible")
        val hidden = SampleValue("hidden")
        val protected = SampleValue("protected")

        original.addObject(visible)
        original.addObject(hidden)
        original.hide(hidden)
        original.bindProtected("user", protected)

        val restored = InMemoryBlackboard("bb-1")
        restored.replaceInternalState(original.internalState())

        assertEquals(listOf(visible, protected), restored.objects)
        assertEquals(protected, restored["user"])

        restored.clear()

        assertEquals(listOf(protected), restored.objects)
        assertEquals(protected, restored["user"])
    }

    @Test
    fun `rejects state for different blackboard id`() {
        val original = InMemoryBlackboard("bb-1")
        val restored = InMemoryBlackboard("bb-2")

        assertThrows(IllegalArgumentException::class.java) {
            restored.replaceInternalState(original.internalState())
        }
    }

    data class SampleValue(
        val value: String,
    )
}
