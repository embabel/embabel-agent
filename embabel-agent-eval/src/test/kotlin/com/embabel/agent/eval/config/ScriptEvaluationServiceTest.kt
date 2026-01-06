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
package com.embabel.agent.eval.config

import com.embabel.agent.eval.client.ObjectContext
import com.embabel.agent.eval.client.Resource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScriptEvaluationServiceTest {

    @Test
    fun `simple math expression`() {
        val scriptEvaluationService = ScriptEvaluationService()
        val context = mapOf("a" to 1, "b" to 2)
        val expression = "a + b"
        val result = scriptEvaluationService.evaluateExpression<Int>(expression, context)
        assertEquals(3, result)
    }

    @Test
    fun `object context expression`() {
        val scriptEvaluationService = ScriptEvaluationService()
        val oc = ObjectContext(
            context = "test",
            resources = listOf(Resource(name = "name", labels = setOf("Thing"), id = "id")),
            functions = emptyList(),
        )
        val context = mapOf("objectContext" to oc)
        val expression = "objectContext != null && objectContext.resources.size == 1"
        val result = scriptEvaluationService.evaluateExpression<Boolean>(
            expression,
            context,
        )
        assertEquals(true, result)
    }

}
