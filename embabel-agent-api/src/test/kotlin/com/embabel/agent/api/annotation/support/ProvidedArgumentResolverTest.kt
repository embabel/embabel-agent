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

import com.embabel.agent.api.annotation.Provided
import com.embabel.agent.test.unit.FakeOperationContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.kotlinFunction

class ProvidedArgumentResolverTest {

    private val operationContext = FakeOperationContext()

    private val myService = MyService("test-value")

    private val contextProvider = object : ContextProvider {
        override fun <T : Any> getFromContext(type: Class<T>): T? {
            @Suppress("UNCHECKED_CAST")
            return if (type == MyService::class.java) myService as T else null
        }

        override fun hasInContext(type: Class<*>): Boolean {
            return type == MyService::class.java
        }
    }

    private val resolver = ProvidedArgumentResolver(contextProvider)

    @Nested
    inner class SupportsParameter {

        private val methodWithAnnotation: Method = this@ProvidedArgumentResolverTest.javaClass.getDeclaredMethod(
            "methodWithProvidedAnnotation",
            MyService::class.java,
        )

        private val methodWithoutAnnotation: Method = this@ProvidedArgumentResolverTest.javaClass.getDeclaredMethod(
            "methodWithoutAnnotation",
            MyService::class.java,
        )

        private val methodWithAnnotationButTypeNotInContext: Method = this@ProvidedArgumentResolverTest.javaClass.getDeclaredMethod(
            "methodWithProvidedAnnotationButTypeNotInContext",
            OtherService::class.java,
        )

        @Test
        fun `supports parameter with @Provided annotation when type is in context`() {
            val javaParameter = methodWithAnnotation.parameters[0]
            val kotlinParameter = methodWithAnnotation.kotlinFunction?.valueParameters?.get(0)

            assertTrue(resolver.supportsParameter(javaParameter, kotlinParameter, operationContext))
        }

        @Test
        fun `does not support parameter without @Provided annotation`() {
            val javaParameter = methodWithoutAnnotation.parameters[0]
            val kotlinParameter = methodWithoutAnnotation.kotlinFunction?.valueParameters?.get(0)

            assertFalse(resolver.supportsParameter(javaParameter, kotlinParameter, operationContext))
        }

        @Test
        fun `does not support parameter when type is not in context`() {
            val javaParameter = methodWithAnnotationButTypeNotInContext.parameters[0]
            val kotlinParameter = methodWithAnnotationButTypeNotInContext.kotlinFunction?.valueParameters?.get(0)

            assertFalse(resolver.supportsParameter(javaParameter, kotlinParameter, operationContext))
        }

        @Test
        fun `supports parameter with null operation context for metadata discovery`() {
            val javaParameter = methodWithAnnotation.parameters[0]
            val kotlinParameter = methodWithAnnotation.kotlinFunction?.valueParameters?.get(0)

            assertTrue(resolver.supportsParameter(javaParameter, kotlinParameter, null))
        }
    }

    @Nested
    inner class ResolveArgument {

        private val methodWithAnnotation: Method = this@ProvidedArgumentResolverTest.javaClass.getDeclaredMethod(
            "methodWithProvidedAnnotation",
            MyService::class.java,
        )

        @Test
        fun `resolves argument from context provider`() {
            val javaParameter = methodWithAnnotation.parameters[0]
            val kotlinParameter = methodWithAnnotation.kotlinFunction?.valueParameters?.get(0)

            val result = resolver.resolveArgument(javaParameter, kotlinParameter, operationContext)

            assertSame(myService, result)
        }

        @Test
        fun `returns null when type not in context`() {
            val emptyContextProvider = object : ContextProvider {
                override fun <T : Any> getFromContext(type: Class<T>): T? = null
                override fun hasInContext(type: Class<*>): Boolean = false
            }
            val emptyResolver = ProvidedArgumentResolver(emptyContextProvider)
            val javaParameter = methodWithAnnotation.parameters[0]
            val kotlinParameter = methodWithAnnotation.kotlinFunction?.valueParameters?.get(0)

            val result = emptyResolver.resolveArgument(javaParameter, kotlinParameter, operationContext)

            assertNull(result)
        }
    }

    @Nested
    inner class ResolveInputBinding {

        private val methodWithAnnotation: Method = this@ProvidedArgumentResolverTest.javaClass.getDeclaredMethod(
            "methodWithProvidedAnnotation",
            MyService::class.java,
        )

        @Test
        fun `returns empty set for input bindings`() {
            val javaParameter = methodWithAnnotation.parameters[0]
            val kotlinParameter = methodWithAnnotation.kotlinFunction?.valueParameters?.get(0)

            val bindings = resolver.resolveInputBinding(javaParameter, kotlinParameter)

            assertTrue(bindings.isEmpty(), "Provided parameters should not create input bindings")
        }
    }

    // Test helper methods
    @Suppress("UNUSED_PARAMETER")
    private fun methodWithProvidedAnnotation(@Provided service: MyService) {}

    @Suppress("UNUSED_PARAMETER")
    private fun methodWithoutAnnotation(service: MyService) {}

    @Suppress("UNUSED_PARAMETER")
    private fun methodWithProvidedAnnotationButTypeNotInContext(@Provided service: OtherService) {}

    // Test types
    data class MyService(val value: String)
    class OtherService
}
