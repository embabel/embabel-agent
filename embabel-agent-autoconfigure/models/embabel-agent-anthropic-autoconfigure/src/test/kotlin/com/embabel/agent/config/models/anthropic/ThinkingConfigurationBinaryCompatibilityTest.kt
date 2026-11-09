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
package com.embabel.agent.config.models.anthropic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Callers compiled before [ThinkingConfiguration.canBeDisabled] link to these JVM signatures: the
 * constructor and `copy` taking only the token budget, and their synthetic `$default` variants.
 * Removing one fails those callers with `NoSuchMethodError`.
 */
class ThinkingConfigurationBinaryCompatibilityTest {

    private val type = ThinkingConfiguration::class.java
    private val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")

    @Test
    fun `the constructors earlier callers link to are still there`() {
        assertEquals(ThinkingConfiguration(tokenBudget = 100), type.getConstructor(Integer::class.java).newInstance(100))
        val withDefaults = type.getConstructor(Integer::class.java, Integer.TYPE, marker)
        assertEquals(ThinkingConfiguration(), withDefaults.newInstance(null, 1, null))
    }

    @Test
    fun `the copy methods earlier callers link to are still there and keep canBeDisabled`() {
        val original = ThinkingConfiguration(tokenBudget = 100, canBeDisabled = true)
        val copy = type.getMethod("copy", Integer::class.java)
        assertEquals(original.copy(tokenBudget = 200), copy.invoke(original, 200))
        val copyWithDefaults = type.getMethod("copy\$default", type, Integer::class.java, Integer.TYPE, Any::class.java)
        assertEquals(original, copyWithDefaults.invoke(null, original, null, 1, null))
        assertEquals(original.copy(tokenBudget = 300), copyWithDefaults.invoke(null, original, 300, 0, null))
    }
}
