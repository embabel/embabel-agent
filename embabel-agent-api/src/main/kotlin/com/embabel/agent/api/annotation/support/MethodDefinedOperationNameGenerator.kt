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
package com.embabel.agent.api.annotation.support

import org.springframework.stereotype.Component

/**
 * Define names for operations defined in methods.
 */
fun interface MethodDefinedOperationNameGenerator {
    /**
     * Generate a qualified name to avoid name clashes.
     * @param instance The instance of the class we are reading
     * @param name The name of the method or property for which we should generate a method
     */
    fun generateName(instance: Any, name: String): String

    companion object {
        @JvmStatic
        operator fun invoke(): MethodDefinedOperationNameGenerator =
            FromClassAndMethodMethodDefinedOperationNameGenerator
    }
}

@Component
internal object FromClassAndMethodMethodDefinedOperationNameGenerator : MethodDefinedOperationNameGenerator {
    override fun generateName(instance: Any, name: String): String {
        // Strip the $ suffix from Kotlin internal methods
        return "${instance.javaClass.name}.${stripDollarSign(name)}"
    }

    private fun stripDollarSign(input: String): String {
        val dollarIndex = input.indexOf('$')
        return if (dollarIndex >= 0) {
            input.substring(0, dollarIndex)
        } else {
            input
        }
    }
}
