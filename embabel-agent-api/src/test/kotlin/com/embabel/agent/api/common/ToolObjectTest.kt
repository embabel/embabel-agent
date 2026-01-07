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

import com.embabel.common.util.StringTransformer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolObjectTest {

    private val testObject = object {
        fun someMethod() = "result"
    }

    @Test
    fun `single object constructor creates list with one object`() {
        val toolObject = ToolObject(testObject)
        assertThat(toolObject.objects).hasSize(1)
        assertThat(toolObject.objects[0]).isSameAs(testObject)
    }

    @Test
    fun `single object constructor uses identity naming strategy by default`() {
        val toolObject = ToolObject(testObject)
        assertThat(toolObject.namingStrategy.transform("test")).isEqualTo("test")
    }

    @Test
    fun `single object constructor uses permissive filter by default`() {
        val toolObject = ToolObject(testObject)
        assertThat(toolObject.filter("anyName")).isTrue()
    }

    @Test
    fun `list constructor preserves all objects`() {
        val obj1 = object {}
        val obj2 = object {}
        val toolObject = ToolObject(listOf(obj1, obj2))
        assertThat(toolObject.objects).containsExactly(obj1, obj2)
    }

    @Test
    fun `throws exception when objects contain Iterable`() {
        val exception = assertThrows<IllegalArgumentException> {
            ToolObject(listOf(listOf("nested")))
        }
        assertThat(exception.message).contains("ToolObject cannot be an Iterable")
    }

    @Test
    fun `withPrefix adds prefix to names`() {
        val toolObject = ToolObject(testObject).withPrefix("mcp__")
        assertThat(toolObject.namingStrategy.transform("myTool")).isEqualTo("mcp__myTool")
    }

    @Test
    fun `withPrefix with empty string does not change name`() {
        val toolObject = ToolObject(testObject).withPrefix("")
        assertThat(toolObject.namingStrategy.transform("myTool")).isEqualTo("myTool")
    }

    @Test
    fun `withPrefix preserves objects and filter`() {
        val filter: (String) -> Boolean = { it.startsWith("get") }
        val toolObject = ToolObject(testObject, filter = filter).withPrefix("prefix_")
        assertThat(toolObject.objects[0]).isSameAs(testObject)
        assertThat(toolObject.filter("getName")).isTrue()
        assertThat(toolObject.filter("setName")).isFalse()
    }

    @Test
    fun `withNamingStrategy replaces naming strategy`() {
        val customStrategy = StringTransformer { it.uppercase() }
        val toolObject = ToolObject(testObject).withNamingStrategy(customStrategy)
        assertThat(toolObject.namingStrategy.transform("myTool")).isEqualTo("MYTOOL")
    }

    @Test
    fun `withNamingStrategy preserves objects and filter`() {
        val filter: (String) -> Boolean = { it.length > 3 }
        val toolObject = ToolObject(testObject, filter = filter)
            .withNamingStrategy { it.reversed() }
        assertThat(toolObject.objects[0]).isSameAs(testObject)
        assertThat(toolObject.filter("long")).isTrue()
        assertThat(toolObject.filter("ab")).isFalse()
    }

    @Test
    fun `withFilter replaces filter`() {
        val toolObject = ToolObject(testObject).withFilter { it.contains("Tool") }
        assertThat(toolObject.filter("myTool")).isTrue()
        assertThat(toolObject.filter("myMethod")).isFalse()
    }

    @Test
    fun `withFilter preserves objects and naming strategy`() {
        val customStrategy = StringTransformer { "prefix_$it" }
        val toolObject = ToolObject(testObject, namingStrategy = customStrategy)
            .withFilter { it.length < 10 }
        assertThat(toolObject.objects[0]).isSameAs(testObject)
        assertThat(toolObject.namingStrategy.transform("test")).isEqualTo("prefix_test")
    }

    @Test
    fun `from returns same ToolObject when input is already ToolObject`() {
        val original = ToolObject(testObject)
        val result = ToolObject.from(original)
        assertThat(result).isSameAs(original)
    }

    @Test
    fun `from wraps non-ToolObject in new ToolObject`() {
        val result = ToolObject.from(testObject)
        assertThat(result.objects).containsExactly(testObject)
        assertThat(result.namingStrategy.transform("test")).isEqualTo("test")
        assertThat(result.filter("anyName")).isTrue()
    }

    @Test
    fun `chaining withPrefix and withFilter works correctly`() {
        val toolObject = ToolObject(testObject)
            .withPrefix("api_")
            .withFilter { it.startsWith("get") }
        assertThat(toolObject.namingStrategy.transform("method")).isEqualTo("api_method")
        assertThat(toolObject.filter("getName")).isTrue()
        assertThat(toolObject.filter("setName")).isFalse()
    }

    @Test
    fun `data class copy preserves unmodified fields`() {
        val customStrategy = StringTransformer { "custom_$it" }
        val original = ToolObject(listOf(testObject), customStrategy) { it.isNotEmpty() }
        val copied = original.copy(namingStrategy = StringTransformer.IDENTITY)
        assertThat(copied.objects).isSameAs(original.objects)
        assertThat(copied.filter("test")).isTrue()
        assertThat(copied.filter("")).isFalse()
        assertThat(copied.namingStrategy.transform("test")).isEqualTo("test")
    }

    @Test
    fun `multiple objects are preserved through transformations`() {
        val obj1 = object {}
        val obj2 = object {}
        val toolObject = ToolObject(listOf(obj1, obj2))
            .withPrefix("p_")
            .withFilter { true }
        assertThat(toolObject.objects).containsExactly(obj1, obj2)
    }
}
