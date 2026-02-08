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

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.CoreToolGroups.MATH_DESCRIPTION
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupDescription
import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.agent.core.ToolGroupPermission
import com.embabel.agent.core.support.safelyGetToolsFrom
import com.embabel.agent.spi.common.Constants
import com.embabel.common.core.types.AssetCoordinates
import com.embabel.common.core.types.Semver

/**
 * Math-related tools exposed as a MatryoshkaTool.
 *
 * When used as a tool group, exposes a single "math" MatryoshkaTool.
 * When the LLM invokes this tool, it reveals the individual math operations
 * (add, subtract, multiply, divide, mean, min, max, floor, ceiling, round).
 *
 * Can be used in two ways:
 * 1. As a tool group - register with the platform for automatic resolution
 * 2. Directly - use [matryoshkaTool] or [innerTools] with PromptRunner
 *
 * Example usage:
 * ```kotlin
 * // As a tool group (exposes single MatryoshkaTool)
 * val toolGroup: ToolGroup = MathTools()
 *
 * // Direct use of the MatryoshkaTool
 * ai.withTool(MathTools().matryoshkaTool)
 *
 * // Direct use of inner tools (bypasses MatryoshkaTool)
 * ai.withTools(MathTools().innerTools)
 * ```
 */
class MathTools : ToolGroup, AssetCoordinates {

    /**
     * Create the MatryoshkaTool for math operations.
     * Convenience method for Java interop.
     */
    fun create(): MatryoshkaTool = matryoshkaTool

    val groupDescription: ToolGroupDescription = MATH_DESCRIPTION

    override val provider: String = Constants.EMBABEL_PROVIDER
    override val version = Semver(0, 1, 0)
    override val name: String get() = javaClass.name

    val permissions: Set<ToolGroupPermission> = emptySet()

    override val metadata: ToolGroupMetadata
        get() = ToolGroupMetadata(
            description = groupDescription,
            name = name,
            provider = provider,
            permissions = permissions,
            version = version,
        )

    /**
     * The inner tools - individual math operations.
     * These are the tools that get revealed when the MatryoshkaTool is invoked.
     */
    val innerTools: List<Tool> by lazy {
        safelyGetToolsFrom(ToolObject(MathOperations()))
    }

    /**
     * The MatryoshkaTool facade that wraps all math operations.
     */
    val matryoshkaTool: MatryoshkaTool by lazy {
        MatryoshkaTool.of(
            name = "math",
            description = "Mathematical operations. Invoke to see available operations " +
                    "including arithmetic, statistics, and rounding functions.",
            innerTools = innerTools,
            childToolUsageNotes = "Use add/subtract/multiply/divide for basic arithmetic. " +
                    "Use mean/min/max for statistics on lists. " +
                    "Use floor/ceiling/round for rounding.",
        )
    }

    /**
     * As a tool group, exposes only the MatryoshkaTool.
     * The LLM sees a single "math" tool; when invoked, the inner tools are revealed.
     */
    override val tools: List<Tool>
        get() = listOf(matryoshkaTool)

    /**
     * Inner class containing the actual math operations.
     * Separated to allow clean extraction via @LlmTool annotations.
     */
    private class MathOperations {

        @LlmTool(description = "add two numbers")
        fun add(a: Double, b: Double) = a + b

        @LlmTool(description = "subtract the second number from the first")
        fun subtract(a: Double, b: Double) = a - b

        @LlmTool(description = "multiply two numbers")
        fun multiply(a: Double, b: Double) = a * b

        @LlmTool(description = "divide the first number by the second")
        fun divide(a: Double, b: Double): String =
            if (b == 0.0) "Cannot divide by zero" else ("" + a / b)

        @LlmTool(description = "find the mean of this list of numbers")
        fun mean(numbers: List<Double>): Double =
            if (numbers.isEmpty()) 0.0 else numbers.sum() / numbers.size

        @LlmTool(description = "find the minimum value in a list of numbers")
        fun min(numbers: List<Double>): Double =
            numbers.minOrNull() ?: Double.NaN

        @LlmTool(description = "find the maximum value in a list of numbers")
        fun max(numbers: List<Double>): Double =
            numbers.maxOrNull() ?: Double.NaN

        @LlmTool(description = "round down to the nearest integer")
        fun floor(number: Double): Double = kotlin.math.floor(number)

        @LlmTool(description = "round up to the nearest integer")
        fun ceiling(number: Double): Double = kotlin.math.ceil(number)

        @LlmTool(description = "round to the nearest integer")
        fun round(number: Double): Double = kotlin.math.round(number).toDouble()
    }

    // Delegate methods for direct access to math operations (for testing and direct use)

    fun add(a: Double, b: Double) = a + b
    fun subtract(a: Double, b: Double) = a - b
    fun multiply(a: Double, b: Double) = a * b
    fun divide(a: Double, b: Double): String =
        if (b == 0.0) "Cannot divide by zero" else ("" + a / b)

    fun mean(numbers: List<Double>): Double =
        if (numbers.isEmpty()) 0.0 else numbers.sum() / numbers.size

    fun min(numbers: List<Double>): Double =
        numbers.minOrNull() ?: Double.NaN

    fun max(numbers: List<Double>): Double =
        numbers.maxOrNull() ?: Double.NaN

    fun floor(number: Double): Double = kotlin.math.floor(number)
    fun ceiling(number: Double): Double = kotlin.math.ceil(number)
    fun round(number: Double): Double = kotlin.math.round(number).toDouble()
}
