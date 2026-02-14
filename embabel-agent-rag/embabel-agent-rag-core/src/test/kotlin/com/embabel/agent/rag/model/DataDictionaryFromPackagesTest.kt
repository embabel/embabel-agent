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
package com.embabel.agent.rag.model

import com.embabel.agent.core.DataDictionary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DataDictionaryFromPackagesTest {

    data class TestPerson(
        override val id: String,
        override val name: String,
        override val description: String,
    ) : NamedEntity

    data class TestBook(
        override val id: String,
        override val name: String,
        override val description: String,
        val isbn: String,
    ) : NamedEntity

    data class NotAnEntity(val value: String)

    @Nested
    inner class DataDictionaryFromPackagesTests {

        @Test
        fun `should find NamedEntity implementations in package`() {
            val dictionary = NamedEntity.dataDictionaryFromPackages(
                "com.embabel.agent.rag.model",
            )
            val classNames = dictionary.jvmTypes.map { it.clazz.name }.toSet()
            assertTrue(classNames.contains(TestPerson::class.java.name))
            assertTrue(classNames.contains(TestBook::class.java.name))
        }

        @Test
        fun `should not include non-NamedEntity types`() {
            val dictionary = NamedEntity.dataDictionaryFromPackages(
                "com.embabel.agent.rag.model",
            )
            val classNames = dictionary.jvmTypes.map { it.clazz.name }.toSet()
            assertFalse(classNames.contains(NotAnEntity::class.java.name))
        }

        @Test
        fun `should not include interfaces`() {
            val dictionary = NamedEntity.dataDictionaryFromPackages(
                "com.embabel.agent.rag.model",
            )
            val classNames = dictionary.jvmTypes.map { it.clazz.name }.toSet()
            assertFalse(classNames.contains(NamedEntity::class.java.name))
            assertFalse(classNames.contains(NamedEntityData::class.java.name))
        }

        @Test
        fun `should return empty dictionary for non-existent package`() {
            val dictionary = NamedEntity.dataDictionaryFromPackages(
                "com.nonexistent.package",
            )
            assertTrue(dictionary.domainTypes.isEmpty())
        }

        @Test
        fun `should find types across multiple packages`() {
            val dictionary = NamedEntity.dataDictionaryFromPackages(
                "com.embabel.agent.rag.model",
                "com.nonexistent.package",
            )
            val classNames = dictionary.jvmTypes.map { it.clazz.name }.toSet()
            assertTrue(classNames.contains(TestPerson::class.java.name))
            assertTrue(classNames.contains(TestBook::class.java.name))
        }

        @Test
        fun `should return distinct results when packages overlap`() {
            val dictionary = NamedEntity.dataDictionaryFromPackages(
                "com.embabel.agent.rag.model",
                "com.embabel.agent.rag",
            )
            val classNames = dictionary.jvmTypes.map { it.clazz.name }
            assertEquals(classNames.size, classNames.distinct().size)
        }

        @Test
        fun `should return empty dictionary when no packages provided`() {
            val dictionary = NamedEntity.dataDictionaryFromPackages()
            assertTrue(dictionary.domainTypes.isEmpty())
        }

        @Test
        fun `should produce domain types usable as JvmTypes`() {
            val dictionary = NamedEntity.dataDictionaryFromPackages(
                "com.embabel.agent.rag.model",
            )
            assertTrue(dictionary.jvmTypes.isNotEmpty())
            assertTrue(dictionary.dynamicTypes.isEmpty())
        }
    }

    @Nested
    inner class PlusOperatorTests {

        @Test
        fun `should combine two dictionaries`() {
            val dict1 = DataDictionary.fromClasses("dict1", TestPerson::class.java)
            val dict2 = DataDictionary.fromClasses("dict2", TestBook::class.java)
            val combined = dict1 + dict2
            assertEquals(2, combined.domainTypes.size)
        }

        @Test
        fun `should preserve left dictionary name`() {
            val dict1 = DataDictionary.fromClasses("first", TestPerson::class.java)
            val dict2 = DataDictionary.fromClasses("second", TestBook::class.java)
            val combined = dict1 + dict2
            assertEquals("first", combined.name)
        }

        @Test
        fun `should deduplicate domain types`() {
            val dict1 = DataDictionary.fromClasses("dict1", TestPerson::class.java)
            val dict2 = DataDictionary.fromClasses("dict2", TestPerson::class.java)
            val combined = dict1 + dict2
            assertEquals(1, combined.domainTypes.size)
        }

        @Test
        fun `should handle empty dictionaries`() {
            val dict1 = DataDictionary.fromDomainTypes("dict1", emptyList())
            val dict2 = DataDictionary.fromClasses("dict2", TestPerson::class.java)
            val combined = dict1 + dict2
            assertEquals(1, combined.domainTypes.size)
        }

        @Test
        fun `should be chainable`() {
            val dict1 = DataDictionary.fromClasses("dict1", TestPerson::class.java)
            val dict2 = DataDictionary.fromClasses("dict2", TestBook::class.java)
            val dict3 = DataDictionary.fromClasses("dict3", NotAnEntity::class.java)
            val combined = dict1 + dict2 + dict3
            assertEquals(3, combined.domainTypes.size)
        }

        @Test
        fun `should work with dataDictionaryFromPackages`() {
            val scanned = NamedEntity.dataDictionaryFromPackages(
                "com.embabel.agent.rag.model",
            )
            val manual = DataDictionary.fromClasses("manual", NotAnEntity::class.java)
            val combined = scanned + manual
            val classNames = combined.jvmTypes.map { it.clazz.name }.toSet()
            assertTrue(classNames.contains(TestPerson::class.java.name))
            assertTrue(classNames.contains(NotAnEntity::class.java.name))
        }
    }
}
