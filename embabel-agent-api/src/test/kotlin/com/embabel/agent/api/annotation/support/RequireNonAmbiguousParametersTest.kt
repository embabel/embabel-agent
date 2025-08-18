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

import com.embabel.agent.api.annotation.Condition
import com.embabel.agent.api.annotation.RequireNameMatch
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

class RequireNonAmbiguousParametersTest {

    private class Demo {
        fun distinctTypes(
            a: String,
            b: Int,
        ) {
        }

        @Condition
        fun twoSameTypeBothAnnotated(
            @RequireNameMatch a: String,
            @RequireNameMatch b: String,
        ) {
        }

        @Condition
        fun twoSameTypeOneAnnotated(
            @RequireNameMatch a: String,
            b: String,
        ) {
        }

        @Condition
        fun threeSameTypeOneAnnotated(
            @RequireNameMatch a: String,
            b: String,
            c: String,
        ) {
        }

        @Condition
        fun threeSameTypeAllAnnotated(
            @RequireNameMatch a: String,
            @RequireNameMatch b: String,
            @RequireNameMatch c: String,
        ) {
        }
    }

    private fun method(name: String): Method = Demo::class.java.declaredMethods.first { it.name == name }

    @Test
    fun `distinct class types`() {
        val m = method("distinctTypes")
        assertDoesNotThrow { requireNonAmbiguousParameters(m) }
    }

    @Test
    fun `two parameters same type both annotated`() {
        val m = method("twoSameTypeBothAnnotated")
        assertDoesNotThrow { requireNonAmbiguousParameters(m) }
    }

    @Test
    fun `two parameters same type one annotated`() {
        val m = method("twoSameTypeOneAnnotated")
        assertThrows(DuplicateParameterTypeException::class.java) {
            requireNonAmbiguousParameters(m)
        }
    }

    @Test
    fun `three parameters same type one annotated`() {
        val m = method("threeSameTypeOneAnnotated")
        assertThrows(DuplicateParameterTypeException::class.java) {
            requireNonAmbiguousParameters(m)
        }
    }

    @Test
    fun `three parameters same type all annotated`() {
        val m = method("threeSameTypeAllAnnotated")
        assertDoesNotThrow { requireNonAmbiguousParameters(m) }
    }
}
