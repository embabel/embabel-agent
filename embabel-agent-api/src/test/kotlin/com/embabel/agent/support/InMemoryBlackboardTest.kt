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
package com.embabel.agent.support

import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.support.InMemoryBlackboard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for InMemoryBlackboard implementation.
 */
class InMemoryBlackboardTest : AbstractBlackboardTest() {

    override fun createBlackboard(): Blackboard {
        return InMemoryBlackboard()
    }

    @Test
    fun `spawn preserves hidden objects independently`() {
        val blackboard = createBlackboard()
        val hidden = Dog("hidden")
        val visible = Dog("visible")
        blackboard["hidden"] = hidden
        blackboard["visible"] = visible
        blackboard.hide(hidden)

        val child = blackboard.spawn()

        assertNull(child["hidden"])
        assertEquals(listOf(visible), child.objects)
        child.hide(visible)
        assertEquals(emptyList<Any>(), child.objects)
        assertEquals(listOf(visible), blackboard.objects)
        assertEquals(visible, blackboard["visible"])
    }
}

interface Organism {
}

open class Animal(
    val name: String,
) : Organism

class Dog(name: String) : Animal(name) {
    override fun toString(): String {
        return "Dog(name='$name')"
    }
}
