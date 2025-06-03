/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import com.embabel.agent.api.common.support.SelfToolGroup
import com.embabel.agent.common.Constants
import com.embabel.agent.core.CoreToolGroups.MATH_DESCRIPTION
import com.embabel.agent.core.ToolGroupDescription
import com.embabel.agent.core.ToolGroupPermission
import com.embabel.common.core.types.Semver
import org.springframework.ai.tool.annotation.Tool

class MathTools : SelfToolGroup {

    override val description: ToolGroupDescription = MATH_DESCRIPTION

    override val provider: String = Constants.EMBABEL_PROVIDER
    override val version = Semver(0, 1, 0)
    override val permissions: Set<ToolGroupPermission>
        get() = emptySet()

    @Tool(description = "add two numbers")
    fun add(a: Double, b: Double) = a + b

    @Tool(description = "subtract the second number from the first")
    fun subtract(a: Double, b: Double) = a - b

    @Tool(description = "multiply two numbers")
    fun multiply(a: Double, b: Double) = a * b

    @Tool(description = "divide the first number by the second")
    fun divide(a: Double, b: Double): String =
        if (b == 0.0) "Cannot divide by zero" else ("" + a / b)

    @Tool(description = "find the mean of this list of numbers")
    fun mean(numbers: List<Double>): Double =
        if (numbers.isEmpty()) 0.0 else numbers.sum() / numbers.size

    @Tool(description = "find the minimum value in a list of numbers")
    fun min(numbers: List<Double>): Double =
        numbers.minOrNull() ?: Double.NaN

    @Tool(description = "find the maximum value in a list of numbers")
    fun max(numbers: List<Double>): Double =
        numbers.maxOrNull() ?: Double.NaN

    @Tool(description = "round down to the nearest integer")
    fun floor(number: Double): Double = kotlin.math.floor(number)

    @Tool(description = "round up to the nearest integer")
    fun ceiling(number: Double): Double = kotlin.math.ceil(number)

    @Tool(description = "round to the nearest integer")
    fun round(number: Double): Double = kotlin.math.round(number).toDouble()
}
