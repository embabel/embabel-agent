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
package com.embabel.agent.tools.math

import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.core.ToolGroup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MathToolsTest {

    private val mathTools = MathTools()

    @Nested
    inner class ToolGroupBehavior {

        @Test
        fun `should implement ToolGroup`() {
            assertThat(mathTools).isInstanceOf(ToolGroup::class.java)
        }

        @Test
        fun `tool group should expose single MatryoshkaTool`() {
            val tools = mathTools.tools
            assertThat(tools).hasSize(1)
            assertThat(tools[0]).isInstanceOf(MatryoshkaTool::class.java)
        }

        @Test
        fun `tool group should NOT expose individual math tools directly`() {
            // When used as a ToolGroup, only the facade is exposed
            // The 10 inner tools (add, subtract, etc.) are NOT directly visible
            val toolNames = mathTools.tools.map { it.definition.name }
            assertThat(toolNames).containsExactly("math")
            assertThat(toolNames).doesNotContain("add", "subtract", "multiply", "divide")
        }

        @Test
        fun `MatryoshkaTool should be named math`() {
            val tool = mathTools.tools[0]
            assertThat(tool.definition.name).isEqualTo("math")
        }

        @Test
        fun `should have metadata with math role`() {
            assertThat(mathTools.metadata.role).isEqualTo("math")
        }

        @Test
        fun `tool group exposes 1 tool but matryoshka contains 10 inner tools`() {
            // Tool group level: 1 tool (the MatryoshkaTool facade)
            assertThat(mathTools.tools).hasSize(1)

            // Inner level: 10 tools (the actual math operations)
            val matryoshka = mathTools.tools[0] as MatryoshkaTool
            assertThat(matryoshka.innerTools).hasSize(10)
        }
    }

    @Nested
    inner class MatryoshkaToolBehavior {

        @Test
        fun `matryoshkaTool should contain inner tools`() {
            val matryoshka = mathTools.matryoshkaTool
            assertThat(matryoshka.innerTools).isNotEmpty()
        }

        @Test
        fun `innerTools should contain all math operations`() {
            val toolNames = mathTools.innerTools.map { it.definition.name }
            assertThat(toolNames).containsExactlyInAnyOrder(
                "add",
                "subtract",
                "multiply",
                "divide",
                "mean",
                "min",
                "max",
                "floor",
                "ceiling",
                "round"
            )
        }

        @Test
        fun `matryoshkaTool and innerTools should be consistent`() {
            val matryoshka = mathTools.matryoshkaTool
            assertThat(matryoshka.innerTools).isEqualTo(mathTools.innerTools)
        }

        @Test
        fun `matryoshkaTool should have descriptive description`() {
            val matryoshka = mathTools.matryoshkaTool
            assertThat(matryoshka.definition.description).contains("Mathematical")
            assertThat(matryoshka.definition.description).contains("operations")
        }
    }

    @Nested
    inner class DirectMethodAccess {

        @Test
        fun testMean() {
            assertEquals(3.0, mathTools.mean(listOf(1.0, 2.0, 3.0, 4.0, 5.0)))
            assertEquals(2.5, mathTools.mean(listOf(1.0, 2.0, 3.0, 4.0)))
            assertEquals(0.0, mathTools.mean(emptyList()))
            assertEquals(42.0, mathTools.mean(listOf(42.0)))
            assertEquals(0.0, mathTools.mean(listOf(-5.0, 5.0)))
        }

        @Test
        fun testMin() {
            assertEquals(1.0, mathTools.min(listOf(1.0, 2.0, 3.0, 4.0, 5.0)))
            assertEquals(-5.0, mathTools.min(listOf(1.0, -5.0, 3.0, 4.0)))
            assertEquals(42.0, mathTools.min(listOf(42.0)))
            assertEquals(Double.NaN, mathTools.min(emptyList()))
        }

        @Test
        fun testMax() {
            assertEquals(5.0, mathTools.max(listOf(1.0, 2.0, 3.0, 4.0, 5.0)))
            assertEquals(10.0, mathTools.max(listOf(1.0, -5.0, 3.0, 10.0)))
            assertEquals(42.0, mathTools.max(listOf(42.0)))
            assertEquals(Double.NaN, mathTools.max(emptyList()))
        }

        @Test
        fun testFloor() {
            assertEquals(3.0, mathTools.floor(3.7))
            assertEquals(3.0, mathTools.floor(3.0))
            assertEquals(-4.0, mathTools.floor(-3.2))
            assertEquals(0.0, mathTools.floor(0.9))
        }

        @Test
        fun testCeiling() {
            assertEquals(4.0, mathTools.ceiling(3.7))
            assertEquals(3.0, mathTools.ceiling(3.0))
            assertEquals(-3.0, mathTools.ceiling(-3.2))
            assertEquals(1.0, mathTools.ceiling(0.9))
        }

        @Test
        fun testRound() {
            assertEquals(4.0, mathTools.round(3.7))
            assertEquals(3.0, mathTools.round(3.0))
            assertEquals(-3.0, mathTools.round(-3.2))
            assertEquals(1.0, mathTools.round(0.9))
            assertEquals(0.0, mathTools.round(0.4))
        }

        @Test
        fun testAdd() {
            assertEquals(5.0, mathTools.add(2.0, 3.0))
            assertEquals(0.0, mathTools.add(-2.0, 2.0))
        }

        @Test
        fun testSubtract() {
            assertEquals(-1.0, mathTools.subtract(2.0, 3.0))
            assertEquals(5.0, mathTools.subtract(3.0, -2.0))
        }

        @Test
        fun testMultiply() {
            assertEquals(6.0, mathTools.multiply(2.0, 3.0))
            assertEquals(-6.0, mathTools.multiply(-2.0, 3.0))
        }

        @Test
        fun testDivide() {
            assertEquals("2.0", mathTools.divide(6.0, 3.0))
            assertEquals("Cannot divide by zero", mathTools.divide(6.0, 0.0))
        }
    }
}
