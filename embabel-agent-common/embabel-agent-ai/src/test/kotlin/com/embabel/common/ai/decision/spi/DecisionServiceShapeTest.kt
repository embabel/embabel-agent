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
package com.embabel.common.ai.decision.spi

import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.decision.DecisionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Pins the binary shape of the decision service interfaces. An existing implementor compiled
 * against the base API must keep linking, so every added member is a JVM default method and the
 * abstract member sets stay as they were.
 */
class DecisionServiceShapeTest {

    private fun Class<*>.abstractMethodNames(): Set<String> =
        methods.filter { Modifier.isAbstract(it.modifiers) }.map { it.name }.toSet()

    private fun Class<*>.declared(name: String): List<Method> = declaredMethods.filter { it.name == name }

    @Test
    fun `DecisionService keeps its abstract members`() {
        assertEquals(setOf("getName", "getProvider", "classify", "assess"), DecisionService::class.java.abstractMethodNames())
    }

    @Test
    fun `ClassificationService keeps its abstract members`() {
        assertEquals(setOf("getName", "getProvider", "classify"), ClassificationService::class.java.abstractMethodNames())
    }

    @Test
    fun `capabilities and the two ask overloads are default methods`() {
        val type = DecisionService::class.java
        val capabilities = type.declared("capabilities")
        val ask = type.declared("ask")
        assertEquals(1, capabilities.size)
        assertEquals(2, ask.size)
        (capabilities + ask).forEach { assertTrue(it.isDefault) { "$it is not a default method" } }
    }

    @Test
    fun `the hook interfaces declare no default methods`() {
        listOf(
            NativeQuestionSetExecution::class.java,
            PropositionAssessment::class.java,
            RatingAssessment::class.java,
        )
            .forEach { hook ->
                assertEquals(1, hook.declaredMethods.size) { "${hook.simpleName} declares ${hook.declaredMethods.toList()}" }
                hook.declaredMethods.forEach { assertTrue(Modifier.isAbstract(it.modifiers)) { "$it is not abstract" } }
            }
    }

    @Test
    fun `DecisionService has no DefaultImpls class`() {
        assertThrows<ClassNotFoundException> {
            Class.forName("com.embabel.common.ai.decision.DecisionService\$DefaultImpls")
        }
    }
}
