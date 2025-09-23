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

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.testing.unit.FakeOperationContext
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.kotlinFunction
import kotlin.test.DefaultAsserter.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActionMethodArgumentResolverTest {

    private val operationContext = FakeOperationContext()

    private val method: Method = javaClass.getDeclaredMethod(
        "arguments", ProcessContext::class.java,
        OperationContext::class.java, Ai::class.java, CustomType::class.java,
    )

    @Test
    fun processContext() {
        val argumentResolver = ProcessContextArgumentResolver()
        val javaParameter = method.parameters[0]
        val kotlinParameter = method.kotlinFunction!!.valueParameters[0]

        assertTrue { argumentResolver.supportsParameter(javaParameter, kotlinParameter, operationContext) }
        assertTrue { argumentResolver.supportsParameter(javaParameter, null, null) }
        assertFalse { argumentResolver.supportsParameter(method.parameters[1], null, null) }

        assertTrue { argumentResolver.resolveInputBinding(javaParameter, kotlinParameter).isEmpty() }

        val arg = argumentResolver.resolveArgument(javaParameter, kotlinParameter, operationContext)
        assertSame(
            message = "Invalid resolved argument",
            expected = operationContext.processContext,
            actual = arg
        )
    }

    @Test
    fun operationContext() {
        val argumentResolver = OperationContextArgumentResolver()
        val javaParameter = method.parameters[1]
        val kotlinParameter = method.kotlinFunction!!.valueParameters[1]

        assertTrue { argumentResolver.supportsParameter(javaParameter, kotlinParameter, operationContext) }
        assertTrue { argumentResolver.supportsParameter(javaParameter, null, null) }
        assertFalse { argumentResolver.supportsParameter(method.parameters[0], null, null) }

        assertTrue { argumentResolver.resolveInputBinding(javaParameter, kotlinParameter).isEmpty() }

        val arg = argumentResolver.resolveArgument(javaParameter, kotlinParameter, operationContext)
        assertSame(
            message = "Invalid resolved argument",
            expected = operationContext,
            actual = arg
        )
    }

    @Test
    fun ai() {
        val argumentResolver = AiArgumentResolver()
        val javaParameter = method.parameters[2]
        val kotlinParameter = method.kotlinFunction!!.valueParameters[2]

        assertTrue { argumentResolver.supportsParameter(javaParameter, kotlinParameter, operationContext) }
        assertTrue { argumentResolver.supportsParameter(javaParameter, null, null) }
        assertFalse { argumentResolver.supportsParameter(method.parameters[0], null, null) }

        assertTrue { argumentResolver.resolveInputBinding(javaParameter, kotlinParameter).isEmpty() }

        val arg = argumentResolver.resolveArgument(javaParameter, kotlinParameter, operationContext)

        assertNotNull(
            message = "Invalid resolved argument",
            actual = arg
        )
    }

    @Test
    fun blackboard() {
        val argumentResolver = BlackboardArgumentResolver()
        val expected = CustomType()
        operationContext.set(IoBinding.DEFAULT_BINDING, expected)

        val javaParameter = method.parameters[3]
        val kotlinParameter = method.kotlinFunction!!.valueParameters[3]
        assertTrue { argumentResolver.supportsParameter(javaParameter, kotlinParameter, operationContext) }
        assertTrue { argumentResolver.supportsParameter(javaParameter, null, null) }

        assertTrue { argumentResolver.resolveInputBinding(javaParameter, kotlinParameter).isNotEmpty() }

        val arg = argumentResolver.resolveArgument(javaParameter, kotlinParameter, operationContext)
        assertSame(
            message = "Invalid resolved argument",
            expected = expected,
            actual = arg
        )
    }


    private fun arguments(
        processContext: ProcessContext,
        operationContext: OperationContext,
        ai: Ai,
        customType: CustomType,
    ) {

    }

    class CustomType

}
