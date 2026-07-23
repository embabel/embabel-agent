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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.core.ActionQos
import com.embabel.agent.core.ActionStatus
import com.embabel.agent.core.ActionStatusCode
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.support.AbstractAction
import com.embabel.agent.core.support.InMemoryBlackboard
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Tests for [CurriedActionTool].
 */
class CurriedActionToolTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `generates unique parameter names when multiple inputs have default 'it' binding`() {
        // Create an action with multiple inputs that all have default "it" binding name
        val action = object : AbstractAction(
            name = "test.multipleInputs",
            description = "Action with multiple inputs",
            pre = emptyList(),
            post = emptyList(),
            cost = { 0.0 },
            value = { 0.0 },
            inputs = setOf(
                IoBinding(type = Cook::class.java),  // Default name "it"
                IoBinding(type = Order::class.java), // Default name "it"
            ),
            outputs = setOf(IoBinding(type = Meal::class.java)),
            toolGroups = emptySet(),
            canRerun = false,
            qos = ActionQos(),
        ) {
            override fun execute(processContext: ProcessContext): ActionStatus {
                return ActionStatus(Duration.ZERO, ActionStatusCode.SUCCEEDED)
            }

            override fun referencedInputProperties(variable: String): Set<String> = emptySet()
        }

        val blackboard = InMemoryBlackboard()
        val tool = CurriedActionTool.createTools(listOf(action), blackboard, objectMapper).first()

        // Get parameter names from the schema
        val parameterNames = tool.definition.inputSchema.parameters.map { it.name }

        // Verify we have the expected number of parameters
        assertEquals(2, parameterNames.size, "Should have 2 parameters")

        // Verify all parameter names are unique
        assertEquals(
            parameterNames.toSet().size,
            parameterNames.size,
            "All parameter names should be unique: $parameterNames"
        )

        // Verify the names are derived from types
        assertTrue(
            parameterNames.any { it.contains("cook", ignoreCase = true) },
            "Should have a parameter name containing 'cook': $parameterNames"
        )
        assertTrue(
            parameterNames.any { it.contains("order", ignoreCase = true) },
            "Should have a parameter name containing 'order': $parameterNames"
        )
    }

    @Test
    fun `preserves explicit binding names when not default`() {
        // Create an action with explicitly named inputs
        val action = object : AbstractAction(
            name = "test.explicitNames",
            description = "Action with explicit input names",
            pre = emptyList(),
            post = emptyList(),
            cost = { 0.0 },
            value = { 0.0 },
            inputs = setOf(
                IoBinding("myInput1", Cook::class.java.name),
                IoBinding("myInput2", Order::class.java.name),
            ),
            outputs = setOf(IoBinding(type = Meal::class.java)),
            toolGroups = emptySet(),
            canRerun = false,
            qos = ActionQos(),
        ) {
            override fun execute(processContext: ProcessContext): ActionStatus {
                return ActionStatus(Duration.ZERO, ActionStatusCode.SUCCEEDED)
            }

            override fun referencedInputProperties(variable: String): Set<String> = emptySet()
        }

        val blackboard = InMemoryBlackboard()
        val tool = CurriedActionTool.createTools(listOf(action), blackboard, objectMapper).first()

        val parameterNames = tool.definition.inputSchema.parameters.map { it.name }

        assertEquals(2, parameterNames.size)
        assertTrue(parameterNames.contains("myInput1"), "Should preserve explicit name 'myInput1'")
        assertTrue(parameterNames.contains("myInput2"), "Should preserve explicit name 'myInput2'")
    }

    @Test
    fun `handles duplicate types with unique suffixes`() {
        // Create an action with multiple inputs of the same type
        val action = object : AbstractAction(
            name = "test.duplicateTypes",
            description = "Action with duplicate input types",
            pre = emptyList(),
            post = emptyList(),
            cost = { 0.0 },
            value = { 0.0 },
            inputs = setOf(
                IoBinding(type = Cook::class.java),  // cook
                IoBinding(type = Cook::class.java),  // Would be cook2 if both were "it"
            ),
            outputs = setOf(IoBinding(type = Meal::class.java)),
            toolGroups = emptySet(),
            canRerun = false,
            qos = ActionQos(),
        ) {
            override fun execute(processContext: ProcessContext): ActionStatus {
                return ActionStatus(Duration.ZERO, ActionStatusCode.SUCCEEDED)
            }

            override fun referencedInputProperties(variable: String): Set<String> = emptySet()
        }

        val blackboard = InMemoryBlackboard()
        val tool = CurriedActionTool.createTools(listOf(action), blackboard, objectMapper).first()

        val parameterNames = tool.definition.inputSchema.parameters.map { it.name }

        // Since Set<IoBinding> deduplicates, we may only have 1 parameter
        // But the names should still be unique
        assertEquals(
            parameterNames.toSet().size,
            parameterNames.size,
            "All parameter names should be unique even with duplicate types: $parameterNames"
        )
    }

    // Test data classes
    data class Cook(val name: String)
    data class Order(val dish: String)
    data class Meal(val dish: String, val cookedBy: String)
}
