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
package com.embabel.agent.spi.support

import com.embabel.agent.core.Blackboard
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [InMemoryContext].
 */
class InMemoryContextTest {

    @Nested
    inner class ConstructorTest {

        @Test
        fun `creates context with id`() {
            val context = InMemoryContext("test-id")

            assertEquals("test-id", context.id)
        }

        @Test
        fun `id is mutable`() {
            val context = InMemoryContext("original-id")
            context.id = "new-id"

            assertEquals("new-id", context.id)
        }

        @Test
        fun `new context has empty objects list`() {
            val context = InMemoryContext("test")

            assertTrue(context.objects.isEmpty())
        }
    }

    @Nested
    inner class BindTest {

        @Test
        fun `bind adds value to objects`() {
            val context = InMemoryContext("test")
            val value = "test-value"

            context.bind("key", value)

            assertTrue(context.objects.contains(value))
        }

        @Test
        fun `bind multiple values`() {
            val context = InMemoryContext("test")

            context.bind("key1", "value1")
            context.bind("key2", "value2")
            context.bind("key3", 123)

            assertEquals(3, context.objects.size)
            assertTrue(context.objects.contains("value1"))
            assertTrue(context.objects.contains("value2"))
            assertTrue(context.objects.contains(123))
        }

        @Test
        fun `bind same key overwrites in map but adds to entries`() {
            val context = InMemoryContext("test")

            context.bind("key", "first")
            context.bind("key", "second")

            // Both values are in entries list
            assertEquals(2, context.objects.size)
            assertTrue(context.objects.contains("first"))
            assertTrue(context.objects.contains("second"))
        }

        @Test
        fun `bind with complex objects`() {
            val context = InMemoryContext("test")
            data class Person(val name: String, val age: Int)
            val person = Person("John", 30)

            context.bind("person", person)

            assertTrue(context.objects.contains(person))
        }
    }

    @Nested
    inner class AddObjectTest {

        @Test
        fun `addObject adds to objects list`() {
            val context = InMemoryContext("test")
            val obj = "standalone-object"

            context.addObject(obj)

            assertTrue(context.objects.contains(obj))
        }

        @Test
        fun `addObject can add multiple objects`() {
            val context = InMemoryContext("test")

            context.addObject("obj1")
            context.addObject("obj2")
            context.addObject(42)

            assertEquals(3, context.objects.size)
        }

        @Test
        fun `addObject and bind can be mixed`() {
            val context = InMemoryContext("test")

            context.bind("key1", "bound-value")
            context.addObject("standalone")
            context.bind("key2", 123)

            assertEquals(3, context.objects.size)
            assertTrue(context.objects.contains("bound-value"))
            assertTrue(context.objects.contains("standalone"))
            assertTrue(context.objects.contains(123))
        }
    }

    @Nested
    inner class ObjectsTest {

        @Test
        fun `objects returns snapshot of entries`() {
            val context = InMemoryContext("test")
            context.addObject("value1")

            val snapshot = context.objects

            context.addObject("value2")

            // Original snapshot should not be affected
            assertEquals(1, snapshot.size)
            assertEquals(2, context.objects.size)
        }

        @Test
        fun `objects preserves insertion order`() {
            val context = InMemoryContext("test")

            context.addObject("first")
            context.addObject("second")
            context.addObject("third")

            assertEquals("first", context.objects[0])
            assertEquals("second", context.objects[1])
            assertEquals("third", context.objects[2])
        }
    }

    @Nested
    inner class InfoStringTest {

        @Test
        fun `infoString includes class name`() {
            val context = InMemoryContext("test-id")

            val info = context.infoString(verbose = true)

            assertTrue(info.contains("InMemoryContext"))
        }

        @Test
        fun `infoString includes id`() {
            val context = InMemoryContext("my-unique-id")

            val info = context.infoString(verbose = true)

            assertTrue(info.contains("my-unique-id"))
        }

        @Test
        fun `infoString includes bound values`() {
            val context = InMemoryContext("test")
            context.bind("myKey", "myValue")

            val info = context.infoString(verbose = true)

            assertTrue(info.contains("myKey"))
            assertTrue(info.contains("myValue"))
        }

        @Test
        fun `infoString includes added objects`() {
            val context = InMemoryContext("test")
            context.addObject("standalone-object")

            val info = context.infoString(verbose = true)

            assertTrue(info.contains("standalone-object"))
        }

        @Test
        fun `infoString with indent`() {
            val context = InMemoryContext("test")

            val info = context.infoString(verbose = true, indent = 2)

            // Should have indentation
            assertNotNull(info)
        }
    }

    @Nested
    inner class PopulateTest {

        @Test
        fun `populate copies bound values to blackboard`() {
            val context = InMemoryContext("test")
            context.bind("key1", "value1")
            context.bind("key2", 42)

            val blackboard = TestBlackboard()
            context.populate(blackboard)

            assertEquals("value1", blackboard.map["key1"])
            assertEquals(42, blackboard.map["key2"])
        }

        @Test
        fun `populate copies standalone objects to blackboard`() {
            val context = InMemoryContext("test")
            context.addObject("standalone1")
            context.addObject("standalone2")

            val blackboard = TestBlackboard()
            context.populate(blackboard)

            assertTrue(blackboard.addedObjects.contains("standalone1"))
            assertTrue(blackboard.addedObjects.contains("standalone2"))
        }

        @Test
        fun `populate does not duplicate bound values as standalone objects`() {
            val context = InMemoryContext("test")
            context.bind("key", "bound-value")

            val blackboard = TestBlackboard()
            context.populate(blackboard)

            // Should be in map but not in addedObjects
            assertEquals("bound-value", blackboard.map["key"])
            assertFalse(blackboard.addedObjects.contains("bound-value"))
        }

        @Test
        fun `populate handles mixed bound and standalone`() {
            val context = InMemoryContext("test")
            context.bind("boundKey", "boundValue")
            context.addObject("standaloneObj")

            val blackboard = TestBlackboard()
            context.populate(blackboard)

            assertEquals("boundValue", blackboard.map["boundKey"])
            assertTrue(blackboard.addedObjects.contains("standaloneObj"))
            assertFalse(blackboard.addedObjects.contains("boundValue"))
        }

        @Test
        fun `populate with empty context does nothing`() {
            val context = InMemoryContext("test")

            val blackboard = TestBlackboard()
            context.populate(blackboard)

            assertTrue(blackboard.map.isEmpty())
            assertTrue(blackboard.addedObjects.isEmpty())
        }
    }

    /**
     * Simple test implementation of Blackboard for testing populate().
     */
    private class TestBlackboard : Blackboard {
        val map = mutableMapOf<String, Any>()
        val addedObjects = mutableListOf<Any>()
        private val conditions = mutableMapOf<String, Boolean>()

        override val blackboardId: String = "test-blackboard"

        override fun get(name: String): Any? = map[name]

        override fun set(key: String, value: Any) {
            map[key] = value
        }

        override fun bind(key: String, value: Any): Blackboard {
            map[key] = value
            addedObjects.add(value)
            return this
        }

        override fun addObject(value: Any): Blackboard {
            addedObjects.add(value)
            return this
        }

        override fun hide(what: Any) {
            // No-op for testing
        }

        override fun <V : Any> getOrPut(name: String, creator: () -> V): V {
            @Suppress("UNCHECKED_CAST")
            return map.getOrPut(name) { creator() } as V
        }

        override fun plusAssign(value: Any) {
            addObject(value)
        }

        override fun plusAssign(pair: Pair<String, Any>) {
            bind(pair.first, pair.second)
        }

        override fun spawn(): Blackboard = TestBlackboard()

        override fun setCondition(key: String, value: Boolean): Blackboard {
            conditions[key] = value
            return this
        }

        override fun getCondition(key: String): Boolean? = conditions[key]

        override fun expressionEvaluationModel(): Map<String, Any> = map.toMap()

        override fun clear() {
            map.clear()
            addedObjects.clear()
            conditions.clear()
        }

        override val objects: List<Any>
            get() = addedObjects.toList()

        override fun infoString(verbose: Boolean?, indent: Int): String = "TestBlackboard"
    }
}
