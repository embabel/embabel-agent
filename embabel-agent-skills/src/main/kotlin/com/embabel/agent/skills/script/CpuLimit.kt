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

import kotlin.math.roundToInt

/**
 * A type-safe CPU limit for a container's `--cpus` flag, stored internally as integer
 * **millicores** (1 core = 1000 millicores).
 *
 * @param millicores the limit in thousandths of a CPU core; must be positive
 */
data class CpuLimit(
    val millicores: Int,
) {
    init {
        require(millicores > 0) { "CPU limit must be positive, was $millicores millicores" }
    }

    /**
     * The `--cpus` token, formatted without floating-point error. Whole cores render as an
     * integer (`1`, `2`); fractional cores render with the minimum needed decimals (`0.5`, `0.25`).
     */
    fun render(): String {
        val whole = millicores / 1000
        val frac = millicores % 1000
        if (frac == 0) return whole.toString()
        // Zero-pad to 3 digits then trim trailing zeros, e.g. 500 -> "5", 250 -> "25", 100 -> "1".
        val fracStr = frac.toString().padStart(3, '0').trimEnd('0')
        return "$whole.$fracStr"
    }

    override fun toString(): String = render()

    companion object {

        /** [cores] whole CPU cores, e.g. `CpuLimit.cores(2)` -> `--cpus 2`. */
        @JvmStatic
        fun cores(cores: Int): CpuLimit = CpuLimit(cores * 1000)

        /** [millicores] thousandths of a core, e.g. `CpuLimit.millicores(500)` -> `--cpus 0.5`. */
        @JvmStatic
        fun millicores(millicores: Int): CpuLimit = CpuLimit(millicores)

        /**
         * Build from a decimal number of cores. The value is quantized to the nearest
         * millicore, so unrepresentable inputs (e.g. `0.1`) are snapped cleanly rather than
         * carrying floating-point noise into the rendered token.
         */
        @JvmStatic
        fun ofCores(cores: Double): CpuLimit = CpuLimit((cores * 1000).roundToInt())
    }
}
