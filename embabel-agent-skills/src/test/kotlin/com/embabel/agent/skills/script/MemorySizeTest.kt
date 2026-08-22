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
package com.embabel.agent.skills.script

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MemorySizeTest {

    @Nested
    inner class Construction {

        @Test
        fun `positive value is accepted`() {
            val size = MemorySize(512, MemoryUnit.MEGABYTES)
            assertEquals(512, size.value)
            assertEquals(MemoryUnit.MEGABYTES, size.memoryUnit)
        }

        @Test
        fun `zero value is rejected`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                MemorySize(0, MemoryUnit.MEGABYTES)
            }
            assertEquals("Memory size must be positive, was 0", ex.message)
        }

        @Test
        fun `negative value is rejected`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                MemorySize(-1, MemoryUnit.GIGABYTES)
            }
            assertEquals("Memory size must be positive, was -1", ex.message)
        }
    }

    @Nested
    inner class Render {

        @Test
        fun `renders bytes token`() {
            assertEquals("1024b", MemorySize(1024, MemoryUnit.BYTES).render())
        }

        @Test
        fun `renders kilobytes token`() {
            assertEquals("256k", MemorySize(256, MemoryUnit.KILOBYTES).render())
        }

        @Test
        fun `renders megabytes token`() {
            assertEquals("512m", MemorySize(512, MemoryUnit.MEGABYTES).render())
        }

        @Test
        fun `renders gigabytes token`() {
            assertEquals("2g", MemorySize(2, MemoryUnit.GIGABYTES).render())
        }

        @Test
        fun `toString delegates to render`() {
            assertEquals("512m", MemorySize(512, MemoryUnit.MEGABYTES).toString())
        }
    }

    @Nested
    inner class Factories {

        @Test
        fun `bytes factory uses BYTES unit`() {
            assertEquals(MemorySize(100, MemoryUnit.BYTES), MemorySize.bytes(100))
        }

        @Test
        fun `kilobytes factory uses KILOBYTES unit`() {
            assertEquals(MemorySize(100, MemoryUnit.KILOBYTES), MemorySize.kilobytes(100))
        }

        @Test
        fun `megabytes factory uses MEGABYTES unit`() {
            assertEquals(MemorySize(512, MemoryUnit.MEGABYTES), MemorySize.megabytes(512))
        }

        @Test
        fun `gigabytes factory uses GIGABYTES unit`() {
            assertEquals(MemorySize(1, MemoryUnit.GIGABYTES), MemorySize.gigabytes(1))
        }
    }

    @Nested
    inner class Units {

        @Test
        fun `each unit exposes its docker suffix`() {
            assertEquals("b", MemoryUnit.BYTES.unit)
            assertEquals("k", MemoryUnit.KILOBYTES.unit)
            assertEquals("m", MemoryUnit.MEGABYTES.unit)
            assertEquals("g", MemoryUnit.GIGABYTES.unit)
            assertEquals("t", MemoryUnit.TERABYTES.unit)
        }

        @Test
        fun `values covers all entries`() {
            assertEquals(5, MemoryUnit.values().size)
            assertEquals(MemoryUnit.MEGABYTES, MemoryUnit.valueOf("MEGABYTES"))
        }
    }

    @Nested
    inner class DataClass {

        @Test
        fun `equals and hashCode are value based`() {
            assertEquals(MemorySize.megabytes(512), MemorySize.megabytes(512))
            assertEquals(MemorySize.megabytes(512).hashCode(), MemorySize.megabytes(512).hashCode())
        }

        @Test
        fun `copy overrides fields`() {
            val original = MemorySize.megabytes(512)
            assertEquals(
                MemorySize(1, MemoryUnit.GIGABYTES),
                original.copy(value = 1, memoryUnit = MemoryUnit.GIGABYTES)
            )
        }

        @Test
        fun `components expose fields`() {
            val (value, unit) = MemorySize.megabytes(512)
            assertEquals(512, value)
            assertEquals(MemoryUnit.MEGABYTES, unit)
        }
    }
}
