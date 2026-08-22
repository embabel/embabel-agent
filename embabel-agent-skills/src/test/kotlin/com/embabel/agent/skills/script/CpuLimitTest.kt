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

class CpuLimitTest {

    @Nested
    inner class Construction {

        @Test
        fun `positive millicores are accepted`() {
            assertEquals(1000, CpuLimit(1000).millicores)
        }

        @Test
        fun `zero millicores are rejected`() {
            val ex = assertThrows(IllegalArgumentException::class.java) { CpuLimit(0) }
            assertEquals("CPU limit must be positive, was 0 millicores", ex.message)
        }

        @Test
        fun `negative millicores are rejected`() {
            val ex = assertThrows(IllegalArgumentException::class.java) { CpuLimit(-1) }
            assertEquals("CPU limit must be positive, was -1 millicores", ex.message)
        }
    }

    @Nested
    inner class Render {

        @Test
        fun `whole single core renders as integer`() {
            assertEquals("1", CpuLimit(1000).render())
        }

        @Test
        fun `whole multiple cores render as integer`() {
            assertEquals("2", CpuLimit(2000).render())
        }

        @Test
        fun `half core trims to single decimal`() {
            assertEquals("0.5", CpuLimit(500).render())
        }

        @Test
        fun `quarter core trims to two decimals`() {
            assertEquals("0.25", CpuLimit(250).render())
        }

        @Test
        fun `one hundred millicores trims to single decimal`() {
            assertEquals("0.1", CpuLimit(100).render())
        }

        @Test
        fun `ten millicores keeps leading zero and trims trailing`() {
            assertEquals("0.01", CpuLimit(10).render())
        }

        @Test
        fun `one millicore keeps all leading zeros`() {
            assertEquals("0.001", CpuLimit(1).render())
        }

        @Test
        fun `whole plus fraction renders both parts`() {
            assertEquals("1.5", CpuLimit(1500).render())
        }

        @Test
        fun `toString delegates to render`() {
            assertEquals("0.5", CpuLimit(500).toString())
        }
    }

    @Nested
    inner class Factories {

        @Test
        fun `cores multiplies by one thousand`() {
            assertEquals(CpuLimit(2000), CpuLimit.cores(2))
        }

        @Test
        fun `millicores maps directly`() {
            assertEquals(CpuLimit(500), CpuLimit.millicores(500))
        }

        @Test
        fun `ofCores quantizes exact decimal`() {
            assertEquals(CpuLimit(1500), CpuLimit.ofCores(1.5))
        }

        @Test
        fun `ofCores rounds unrepresentable decimal to nearest millicore`() {
            // 0.1 is not exactly representable in binary floating point.
            assertEquals(CpuLimit(100), CpuLimit.ofCores(0.1))
        }

        @Test
        fun `ofCores rounds half millicore up`() {
            // 1.2345 cores -> 1234.5 millicores -> rounds to 1235
            assertEquals(CpuLimit(1235), CpuLimit.ofCores(1.2345))
        }
    }

    @Nested
    inner class DataClass {

        @Test
        fun `equals and hashCode are value based`() {
            assertEquals(CpuLimit(500), CpuLimit(500))
            assertEquals(CpuLimit(500).hashCode(), CpuLimit(500).hashCode())
        }

        @Test
        fun `copy overrides millicores`() {
            assertEquals(CpuLimit(750), CpuLimit(500).copy(millicores = 750))
        }

        @Test
        fun `component and property expose millicores`() {
            val (millicores) = CpuLimit(500)
            assertEquals(500, millicores)
        }
    }
}
