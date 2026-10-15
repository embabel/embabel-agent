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
package com.embabel.agent.api.common

import com.embabel.common.ai.decision.support.StubDecisionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Pins the abstract members of [Ai] and [PlatformServices], so that existing implementations keep
 * compiling and linking when members are added.
 */
class AiShapeTest {

    private fun signature(method: Method): String =
        "${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"

    private fun abstractMembers(type: Class<*>): Set<String> =
        type.declaredMethods.filter { Modifier.isAbstract(it.modifiers) }.map(::signature).toSet()

    private fun declared(type: Class<*>, name: String): Method =
        type.declaredMethods.single { it.name == name && it.parameterCount == 0 }

    @Test
    fun `Ai declares exactly the two abstract methods it has always had`() {
        assertEquals(
            setOf("withEmbeddingService(ModelSelectionCriteria)", "withLlm(LlmOptions)"),
            abstractMembers(Ai::class.java),
        )
    }

    @Test
    fun `the Ai selector methods are default methods`() {
        assertTrue(declared(Ai::class.java, "decisions").isDefault)
        assertTrue(declared(Ai::class.java, "classifications").isDefault)
    }

    @Test
    fun `PlatformServices keeps its abstract member set`() {
        assertEquals(
            setOf(
                "getAgentPlatform()",
                "getLlmOperations()",
                "getEventListener()",
                "getOperationScheduler()",
                "getAgentProcessRepository()",
                "getAsyncer()",
                "getLogicalExpressionParser()",
                "getObjectMapper()",
                "getOutputChannel()",
                "getTemplateRenderer()",
                "autonomy()",
                "modelProvider()",
                "conversationFactoryProvider()",
                "withEventListener(AgenticEventListener)",
                "actionQosProperties()",
            ),
            abstractMembers(PlatformServices::class.java),
        )
        assertTrue(declared(PlatformServices::class.java, "decisionServices").isDefault)
    }

    @Test
    fun `a Java Ai with only the abstract methods selects a supplied service`() {
        val stub = StubDecisionService.builder("triage-stub").build()
        val ai: Ai = AiDecisionSelectorsJavaTest.MinimalAi()
        assertSame(stub, ai.decisions().using(stub))
    }
}
