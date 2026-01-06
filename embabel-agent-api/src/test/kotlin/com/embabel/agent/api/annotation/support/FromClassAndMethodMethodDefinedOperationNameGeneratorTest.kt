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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.support.Dog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FromClassAndMethodMethodDefinedOperationNameGeneratorTest {

    @Test
    fun `should generate method name from class and method without $ prefix`() {
        val generator = FromClassAndMethodMethodDefinedOperationNameGenerator
        val name = generator.generateName(
            Dog("Disco"),
            "bark"
        )
        assertEquals("${Dog::class.java.name}.bark", name)
    }

    @Test
    fun `should generate method name from class and method with Kotlin internal $ prefix`() {
        val name = FromClassAndMethodMethodDefinedOperationNameGenerator.generateName(
            Dog("Duke"),
            "bark\$special_package"
        )
        assertEquals("${Dog::class.java.name}.bark", name)
    }

    @Test
    fun `should respect inner class name`() {
        val generator = FromClassAndMethodMethodDefinedOperationNameGenerator
        val name = generator.generateName(
            Thing.InnerClass(),
            "getHomePage"
        )
        assertEquals("${Thing.InnerClass::class.java.name}.getHomePage", name)
    }
}

class Thing {

    class InnerClass {
        fun getHomePage() {}
    }
}
