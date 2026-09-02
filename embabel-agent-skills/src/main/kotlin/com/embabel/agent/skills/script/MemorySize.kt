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

/**
 * Unit for a [MemorySize], mapped to the single-letter suffix that Docker/Podman's
 * `--memory` flag understands (`b`, `k`, `m`, `g`, `t`).
 */
enum class MemoryUnit(val unit: String) {
    BYTES("b"),
    KILOBYTES("k"),
    MEGABYTES("m"),
    GIGABYTES("g"),
    TERABYTES("t"),
}

/**
 * A type-safe memory quantity: a numeric [value] paired with a [unit].
 *
 * Replaces the previous free-form `String` memory limit (e.g. `"512m"`) so callers cannot
 * pass an unparseable/typo'd value. [render] produces the exact token the container runtime
 * expects for its `--memory` flag (e.g. `512m`, `1g`).
 *
 * @param value the numeric amount; must be positive
 * @param unit the unit the [value] is expressed in
 */
data class MemorySize(
    val value: Long,
    val memoryUnit: MemoryUnit,
) {
    init {
        require(value > 0) { "Memory size must be positive, was $value" }
    }

    /** The `--memory` token for Docker/Podman, e.g. `512m`. */
    fun render(): String = "$value${memoryUnit.unit}"

    override fun toString(): String = render()

    companion object {

        /** [bytes] bytes. eg: 2048 -> 2048b */
        @JvmStatic
        fun bytes(bytes: Long): MemorySize = MemorySize(bytes, MemoryUnit.BYTES)

        /** [kilobytes] kilobytes. eg: 512 -> 512k */
        @JvmStatic
        fun kilobytes(kilobytes: Long): MemorySize = MemorySize(kilobytes, MemoryUnit.KILOBYTES)

        /** [megabytes] megabytes. eg: 512 -> 512m */
        @JvmStatic
        fun megabytes(megabytes: Long): MemorySize = MemorySize(megabytes, MemoryUnit.MEGABYTES)

        /** [gigabytes] gigabytes. eg: 1 -> 1g */
        @JvmStatic
        fun gigabytes(gigabytes: Long): MemorySize = MemorySize(gigabytes, MemoryUnit.GIGABYTES)

        /** [terabytes] terabytes. eg: 1 -> 1t*/
        fun terabytes(terabytes: Long): MemorySize = MemorySize(terabytes, MemoryUnit.TERABYTES)
    }
}
