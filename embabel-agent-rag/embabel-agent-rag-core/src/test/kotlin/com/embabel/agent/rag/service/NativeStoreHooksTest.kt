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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.model.RetrievableEntity
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Test interface for native store hook tests.
 */
interface Product : NamedEntity {
    val price: Double
    val sku: String
}

/**
 * Another test interface.
 */
interface Category : NamedEntity {
    val parentCategory: String?
}

/**
 * Concrete implementation of Product for native store simulation.
 */
data class ProductImpl(
    override val id: String,
    override val name: String,
    override val description: String,
    override val price: Double,
    override val sku: String,
    override val uri: String? = null,
    override val metadata: Map<String, Any?> = emptyMap(),
) : Product {
    override fun labels(): Set<String> = setOf("Product", RetrievableEntity.ENTITY_LABEL)
}

/**
 * Tests for native store hooks in [NamedEntityDataRepository].
 *
 * These hooks allow implementations to provide native store mappings (e.g., JPA)
 * that take precedence over generic label-based lookup.
 */
class NativeStoreHooksTest {

    private val testDictionary = DataDictionary.fromClasses(
        Product::class.java,
        Category::class.java
    )

    @Nested
    inner class DefaultBehaviorTest {

        private lateinit var repository: InMemoryNamedEntityDataRepository

        @BeforeEach
        fun setup() {
            repository = InMemoryNamedEntityDataRepository(testDictionary)
        }

        @Test
        fun `findNativeById returns null by default`() {
            val result = repository.findNativeById("any-id", Product::class.java)
            assertNull(result)
        }

        @Test
        fun `findNativeAll returns null by default`() {
            val result = repository.findNativeAll(Product::class.java)
            assertNull(result)
        }

        @Test
        fun `findTypedById falls back to generic lookup when native returns null`() {
            repository.save(
                SimpleNamedEntityData(
                    id = "product-1",
                    name = "Widget",
                    description = "A useful widget",
                    labels = setOf("Product"),
                    properties = mapOf("price" to 19.99, "sku" to "WDG-001")
                )
            )

            val result = repository.findTypedById("product-1", Product::class.java)

            assertNotNull(result)
            assertEquals("product-1", result!!.id)
            assertEquals("Widget", result.name)
            assertEquals(19.99, result.price)
            assertEquals("WDG-001", result.sku)
        }

        @Test
        fun `findAll falls back to generic lookup when native returns null`() {
            repository.save(
                SimpleNamedEntityData(
                    id = "product-1",
                    name = "Widget",
                    description = "A widget",
                    labels = setOf("Product"),
                    properties = mapOf("price" to 10.0, "sku" to "WDG-001")
                )
            )
            repository.save(
                SimpleNamedEntityData(
                    id = "product-2",
                    name = "Gadget",
                    description = "A gadget",
                    labels = setOf("Product"),
                    properties = mapOf("price" to 20.0, "sku" to "GDG-001")
                )
            )

            val results = repository.findAll(Product::class.java)

            assertEquals(2, results.size)
            assertTrue(results.any { it.id == "product-1" })
            assertTrue(results.any { it.id == "product-2" })
        }
    }

    @Nested
    inner class NativeOverrideTest {

        @Test
        fun `findTypedById uses native result when available`() {
            val nativeProduct = ProductImpl(
                id = "native-1",
                name = "Native Product",
                description = "From native store",
                price = 99.99,
                sku = "NAT-001"
            )

            val repository = object : InMemoryNamedEntityDataRepository(testDictionary) {
                override fun isNativeType(type: Class<*>): Boolean = type == Product::class.java

                @Suppress("UNCHECKED_CAST")
                override fun <T : NamedEntity> findNativeById(id: String, type: Class<T>): T? {
                    return if (id == "native-1" && type == Product::class.java) {
                        nativeProduct as T
                    } else {
                        null
                    }
                }
            }

            // Also save a different entity with same ID in generic store
            repository.save(
                SimpleNamedEntityData(
                    id = "native-1",
                    name = "Generic Product",
                    description = "From generic store",
                    labels = setOf("Product"),
                    properties = mapOf("price" to 1.0, "sku" to "GEN-001")
                )
            )

            val result = repository.findTypedById("native-1", Product::class.java)

            assertNotNull(result)
            // Should get native result, not generic
            assertEquals("Native Product", result!!.name)
            assertEquals(99.99, result.price)
            assertEquals("NAT-001", result.sku)
        }

        @Test
        fun `findTypedById falls back to generic when native returns null for different type`() {
            val nativeProduct = ProductImpl(
                id = "native-1",
                name = "Native Product",
                description = "From native store",
                price = 99.99,
                sku = "NAT-001"
            )

            val repository = object : InMemoryNamedEntityDataRepository(testDictionary) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : NamedEntity> findNativeById(id: String, type: Class<T>): T? {
                    // Only handle Product type natively
                    return if (type == Product::class.java && id == "native-1") {
                        nativeProduct as T
                    } else {
                        null
                    }
                }
            }

            // Save a Category in generic store
            repository.save(
                SimpleNamedEntityData(
                    id = "category-1",
                    name = "Electronics",
                    description = "Electronic items",
                    labels = setOf("Category"),
                    properties = emptyMap()
                )
            )

            // Category should fall back to generic lookup
            val categoryResult = repository.findTypedById("category-1", Category::class.java)

            assertNotNull(categoryResult)
            assertEquals("Electronics", categoryResult!!.name)
        }

        @Test
        fun `findAll uses native result when available`() {
            val nativeProducts = listOf(
                ProductImpl("nat-1", "Native 1", "Desc 1", 10.0, "SKU-1"),
                ProductImpl("nat-2", "Native 2", "Desc 2", 20.0, "SKU-2")
            )

            val repository = object : InMemoryNamedEntityDataRepository(testDictionary) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : NamedEntity> findNativeAll(type: Class<T>): List<T>? {
                    return if (type == Product::class.java) {
                        nativeProducts as List<T>
                    } else {
                        null
                    }
                }
            }

            // Save different products in generic store
            repository.save(
                SimpleNamedEntityData(
                    id = "gen-1",
                    name = "Generic 1",
                    description = "Generic product",
                    labels = setOf("Product"),
                    properties = mapOf("price" to 1.0, "sku" to "GEN-1")
                )
            )

            val results = repository.findAll(Product::class.java)

            // Should get native results, not generic
            assertEquals(2, results.size)
            assertTrue(results.all { it.name.startsWith("Native") })
            assertTrue(results.none { it.name.startsWith("Generic") })
        }

        @Test
        fun `findAll falls back to generic when native returns null for different type`() {
            val nativeProducts = listOf(
                ProductImpl("nat-1", "Native 1", "Desc 1", 10.0, "SKU-1")
            )

            val repository = object : InMemoryNamedEntityDataRepository(testDictionary) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : NamedEntity> findNativeAll(type: Class<T>): List<T>? {
                    // Only handle Product type natively
                    return if (type == Product::class.java) {
                        nativeProducts as List<T>
                    } else {
                        null
                    }
                }
            }

            // Save categories in generic store
            repository.save(
                SimpleNamedEntityData(
                    id = "cat-1",
                    name = "Category 1",
                    description = "A category",
                    labels = setOf("Category"),
                    properties = emptyMap()
                )
            )

            // Category should fall back to generic lookup
            val categoryResults = repository.findAll(Category::class.java)

            assertEquals(1, categoryResults.size)
            assertEquals("Category 1", categoryResults.first().name)
        }

        @Test
        fun `findAll returns empty list from native when no entities exist`() {
            val repository = object : InMemoryNamedEntityDataRepository(testDictionary) {
                @Suppress("UNCHECKED_CAST")
                override fun <T : NamedEntity> findNativeAll(type: Class<T>): List<T>? {
                    return if (type == Product::class.java) {
                        emptyList()
                    } else {
                        null
                    }
                }
            }

            // Save something in generic store that would match
            repository.save(
                SimpleNamedEntityData(
                    id = "gen-1",
                    name = "Generic 1",
                    description = "Generic product",
                    labels = setOf("Product"),
                    properties = mapOf("price" to 1.0, "sku" to "GEN-1")
                )
            )

            // Native returns empty list, so should get empty (not fallback to generic)
            val results = repository.findAll(Product::class.java)

            assertTrue(results.isEmpty())
        }

        @Test
        fun `findTypedById returns null when native returns null and entity not in generic store`() {
            val repository = object : InMemoryNamedEntityDataRepository(testDictionary) {
                override fun <T : NamedEntity> findNativeById(id: String, type: Class<T>): T? = null
            }

            val result = repository.findTypedById("nonexistent", Product::class.java)

            assertNull(result)
        }
    }

    @Nested
    inner class MixedNativeAndGenericTest {

        @Test
        fun `can have native mapping for some types and generic for others`() {
            val nativeProduct = ProductImpl("prod-1", "Native Product", "Native", 50.0, "NAT")

            val repository = object : InMemoryNamedEntityDataRepository(testDictionary) {
                override fun isNativeType(type: Class<*>): Boolean = type == Product::class.java

                @Suppress("UNCHECKED_CAST")
                override fun <T : NamedEntity> findNativeById(id: String, type: Class<T>): T? {
                    return if (type == Product::class.java && id == "prod-1") {
                        nativeProduct as T
                    } else {
                        null
                    }
                }

                @Suppress("UNCHECKED_CAST")
                override fun <T : NamedEntity> findNativeAll(type: Class<T>): List<T>? {
                    return if (type == Product::class.java) {
                        listOf(nativeProduct) as List<T>
                    } else {
                        null
                    }
                }
            }

            // Save Category in generic store
            repository.save(
                SimpleNamedEntityData(
                    id = "cat-1",
                    name = "Electronics",
                    description = "Electronics category",
                    labels = setOf("Category"),
                    properties = emptyMap()
                )
            )

            // Product comes from native
            val product = repository.findTypedById("prod-1", Product::class.java)
            assertNotNull(product)
            assertEquals("Native Product", product!!.name)

            // Category comes from generic
            val category = repository.findTypedById("cat-1", Category::class.java)
            assertNotNull(category)
            assertEquals("Electronics", category!!.name)

            // findAll Product from native
            val products = repository.findAll(Product::class.java)
            assertEquals(1, products.size)
            assertEquals("Native Product", products.first().name)

            // findAll Category from generic
            val categories = repository.findAll(Category::class.java)
            assertEquals(1, categories.size)
            assertEquals("Electronics", categories.first().name)
        }
    }
}
