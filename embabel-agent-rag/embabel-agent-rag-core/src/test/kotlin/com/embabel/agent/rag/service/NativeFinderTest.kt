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
package com.embabel.agent.rag.service

import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.service.support.ChainedNativeFinder
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NativeFinderTest {

    @Nested
    inner class NoneTest {

        @Test
        fun `findById returns null`() {
            val result = NativeFinder.NONE.findById("any-id", Product::class.java)
            assertNull(result)
        }

        @Test
        fun `findAll returns null`() {
            val result = NativeFinder.NONE.findAll(Product::class.java)
            assertNull(result)
        }
    }

    @Nested
    inner class ChainedNativeFinderTest {

        private val nativeProduct = ProductImpl(
            id = "prod-1",
            name = "Native Product",
            description = "From native store",
            price = 99.99,
            sku = "NAT-001"
        )

        @Test
        fun `returns result from first finder that returns non-null`() {
            val finder1 = object : NativeFinder {
                @Suppress("UNCHECKED_CAST")
                override fun <T : NamedEntity> findById(id: String, type: Class<T>): T? =
                    nativeProduct as T
            }
            val finder2 = object : NativeFinder {
                override fun <T : NamedEntity> findById(id: String, type: Class<T>): T? =
                    throw AssertionError("Should not be called")
            }

            val chained = ChainedNativeFinder(finder1, finder2)
            val result = chained.findById("prod-1", Product::class.java)

            assertNotNull(result)
            assertEquals("Native Product", result!!.name)
        }

        @Test
        fun `skips finders that return null and returns from later finder`() {
            val finder1 = object : NativeFinder {} // returns null by default
            val finder2 = object : NativeFinder {
                @Suppress("UNCHECKED_CAST")
                override fun <T : NamedEntity> findById(id: String, type: Class<T>): T? =
                    nativeProduct as T
            }

            val chained = ChainedNativeFinder(finder1, finder2)
            val result = chained.findById("prod-1", Product::class.java)

            assertNotNull(result)
            assertEquals("Native Product", result!!.name)
        }

        @Test
        fun `returns null when all finders return null`() {
            val finder1 = object : NativeFinder {}
            val finder2 = object : NativeFinder {}

            val chained = ChainedNativeFinder(finder1, finder2)
            val result = chained.findById("prod-1", Product::class.java)

            assertNull(result)
        }

        @Test
        fun `empty chain returns null`() {
            val chained = ChainedNativeFinder()
            val result = chained.findById("prod-1", Product::class.java)

            assertNull(result)
        }

        @Test
        fun `findAll follows same first-non-null semantics`() {
            val products = listOf(nativeProduct)
            val finder1 = object : NativeFinder {} // returns null by default
            val finder2 = object : NativeFinder {
                @Suppress("UNCHECKED_CAST")
                override fun <T : NamedEntity> findAll(type: Class<T>): List<T>? =
                    products as List<T>
            }

            val chained = ChainedNativeFinder(finder1, finder2)
            val result = chained.findAll(Product::class.java)

            assertNotNull(result)
            assertEquals(1, result!!.size)
            assertEquals("Native Product", result[0].name)
        }

        @Test
        fun `findAll returns null when all finders return null`() {
            val chained = ChainedNativeFinder(object : NativeFinder {}, object : NativeFinder {})
            val result = chained.findAll(Product::class.java)

            assertNull(result)
        }

        @Test
        fun `exception in one finder propagates`() {
            val finder = object : NativeFinder {
                override fun <T : NamedEntity> findById(id: String, type: Class<T>): T? {
                    throw RuntimeException("Native store failure")
                }
            }

            val chained = ChainedNativeFinder(finder)
            assertThrows(RuntimeException::class.java) {
                chained.findById("prod-1", Product::class.java)
            }
        }
    }
}
