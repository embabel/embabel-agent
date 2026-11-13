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
package com.embabel.common.ai.model

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Callers compiled against earlier releases link to these JVM signatures of [LlmOptions]: Java and
 * positional Kotlin calls to the constructor and `copy`, and Kotlin calls with defaults to the
 * synthetic `$default` variants. Removing one fails those callers with `NoSuchMethodError`;
 * the ones invoked here must also behave as they did.
 */
class LlmOptionsBinaryCompatibilityTest {

    private val fields = arrayOf(
        ModelSelectionCriteria::class.java, String::class.java, String::class.java,
        java.lang.Double::class.java, java.lang.Double::class.java, Integer::class.java,
        java.lang.Double::class.java, Integer::class.java, java.lang.Double::class.java,
        Thinking::class.java, Duration::class.java, Map::class.java,
    )
    private val defaultsMarker = arrayOf(Integer.TYPE, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"))

    @Test
    fun `the constructors earlier callers link to are still there and fill in defaults`() {
        assertDoesNotThrow { LlmOptions::class.java.getConstructor(*fields) }
        val withDefaults = LlmOptions::class.java.getConstructor(*fields, *defaultsMarker)
        val allOmitted = (1 shl fields.size) - 1
        assertEquals(LlmOptions(timeout = null), withDefaults.newInstance(*arrayOfNulls<Any>(fields.size), allOmitted, null))
    }

    @Test
    fun `the copy methods earlier callers link to are still there and keep the effort`() {
        assertDoesNotThrow { LlmOptions::class.java.getMethod("copy", *fields) }
        val copyWithDefaults = LlmOptions::class.java.getMethod("copy\$default", LlmOptions::class.java, *fields, Integer.TYPE, Any::class.java)
        val original = LlmOptions(model = "m").withReasoningEffort(ReasoningEffort.LOW)
        val args = arrayOfNulls<Any>(fields.size).also { it[3] = 0.3 } // temperature
        val allButTemperature = ((1 shl fields.size) - 1) and (1 shl 3).inv()
        val copied = copyWithDefaults.invoke(null, original, *args, allButTemperature, null) as LlmOptions
        assertEquals(original.copy(temperature = 0.3), copied)
    }

    /** Compiling is the check: an ambiguous overload would fail this file. */
    @Test
    @Suppress("DEPRECATION")
    fun `Kotlin named and twelve-argument copy calls still compile and keep the effort`() {
        val original = LlmOptions(model = "m").withReasoningEffort(ReasoningEffort.LOW)
        assertEquals(ReasoningEffort.LOW, original.copy(temperature = 0.3).reasoningEffort)
        val positional = original.copy(null, "m", null, 0.3, null, null, null, null, null, null, null, emptyMap())
        assertEquals(original.copy(temperature = 0.3), positional)
    }
}
