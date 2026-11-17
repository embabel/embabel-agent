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
package com.embabel.common.ai.decision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

class DecisionCallValuesTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `option id joins namespace and name with a dot and equality uses id and type`() {
        val option = DecisionOption.of("acme.vendor", "max-depth", Int::class.javaObjectType)

        assertEquals("acme.vendor.max-depth", option.id)
        assertEquals("acme.vendor.max-depth", option.toString())
        assertEquals(DecisionOption.of("acme.vendor", "max-depth", Int::class.javaObjectType), option)
        assertEquals(
            DecisionOption.of("acme.vendor", "max-depth", Int::class.javaObjectType).hashCode(),
            option.hashCode(),
        )
        assertNotEquals(DecisionOption.of("acme.vendor", "max-depth", String::class.java), option)
        assertNotEquals(DecisionOption.of("acme.vendor", "min-depth", Int::class.javaObjectType), option)
    }

    @Test
    fun `option rejects a blank name`() {
        assertThrows<IllegalArgumentException> { DecisionOption.of("acme", "", String::class.java) }
        assertThrows<IllegalArgumentException> { DecisionOption.of("acme", "  ", String::class.java) }
        assertThrows<IllegalArgumentException> { DecisionOption.of("", "name", String::class.java) }
    }

    @Test
    fun `option rejects an upper-case segment`() {
        assertThrows<IllegalArgumentException> { DecisionOption.of("acme.Vendor", "name", String::class.java) }
        assertThrows<IllegalArgumentException> { DecisionOption.of("acme", "maxDepth", String::class.java) }
    }

    @Test
    fun `option rejects empty segments and other characters`() {
        assertThrows<IllegalArgumentException> { DecisionOption.of("acme..vendor", "name", String::class.java) }
        assertThrows<IllegalArgumentException> { DecisionOption.of(".acme", "name", String::class.java) }
        assertThrows<IllegalArgumentException> { DecisionOption.of("acme", "name.", String::class.java) }
        assertThrows<IllegalArgumentException> { DecisionOption.of("acme", "max_depth", String::class.java) }
    }

    @Test
    fun `timeout option id is embabel timeout and its type is Duration`() {
        assertEquals("embabel.timeout", DecisionOptions.TIMEOUT.id)
        assertEquals(Duration::class.java, DecisionOptions.TIMEOUT.type)
        assertEquals("embabel", DecisionOptions.TIMEOUT.namespace)
        assertEquals("timeout", DecisionOptions.TIMEOUT.name)
    }

    @Test
    fun `text input has kind text and equals another with the same value`() {
        val input = TextDecisionInput("hello")

        assertEquals(DecisionInput.TEXT, input.kind)
        assertEquals("text", input.kind)
        assertEquals(TextDecisionInput("hello"), input)
        assertEquals(TextDecisionInput("hello").hashCode(), input.hashCode())
        assertNotEquals(TextDecisionInput("bye"), input)
    }

    @Test
    fun `json input rejects a text node`() {
        assertThrows<IllegalArgumentException> { JsonDecisionInput(mapper.readTree("\"just text\"")) }
        assertThrows<IllegalArgumentException> { JsonDecisionInput(mapper.readTree("42")) }
    }

    @Test
    fun `json input accepts objects and arrays and has kind json`() {
        assertEquals(DecisionInput.JSON, JsonDecisionInput(mapper.readTree("""{"a":1}""")).kind)
        assertEquals("json", JsonDecisionInput(mapper.readTree("[1,2]")).kind)
    }

    @Test
    fun `json input is unaffected when the caller mutates the node after construction`() {
        val node = mapper.readTree("""{"a":1}""") as ObjectNode
        val input = JsonDecisionInput(node)

        node.put("a", 99)
        node.put("b", 2)

        assertEquals(mapper.readTree("""{"a":1}"""), input.value)
        assertEquals("""{"a":1}""", input.toText())
    }

    @Test
    fun `json input value returns a fresh copy each time`() {
        val input = JsonDecisionInput(mapper.readTree("""{"a":1}"""))

        (input.value as ObjectNode).put("a", 99)

        assertEquals(mapper.readTree("""{"a":1}"""), input.value)
    }

    @Test
    fun `json inputs are equal when their nodes are equal`() {
        val first = JsonDecisionInput(mapper.readTree("""{"a":[1,2]}"""))
        val second = JsonDecisionInput(mapper.readTree("""{"a":[1,2]}"""))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(JsonDecisionInput(mapper.readTree("""{"a":[2,1]}""")), first)
    }

    @Test
    fun `json input toText keeps member order`() {
        assertEquals("""{"b":1,"a":2}""", JsonDecisionInput(mapper.readTree("""{ "b": 1, "a": 2 }""")).toText())
    }

    @Test
    fun `usage rejects a negative count`() {
        assertThrows<IllegalArgumentException> { DecisionUsage(inputTokens = -1) }
        assertThrows<IllegalArgumentException> { DecisionUsage(outputTokens = -1) }
        assertThrows<IllegalArgumentException> { DecisionUsage(totalTokens = -1) }
        assertThrows<IllegalArgumentException> { DecisionUsage(cacheReadInputTokens = -1) }
        assertThrows<IllegalArgumentException> { DecisionUsage(cacheWriteInputTokens = -1) }
        assertThrows<IllegalArgumentException> { DecisionUsage(reasoningTokens = -1) }
    }

    @Test
    fun `usage accepts zero and null counts`() {
        val usage = DecisionUsage(inputTokens = 0, outputTokens = 0)

        assertEquals(0L, usage.inputTokens)
        assertEquals(null, usage.totalTokens)
        assertEquals(DecisionUsage(null, null, null, null, null, null), DecisionUsage())
    }

    @Test
    fun `usage JSON omits null counts`() {
        val json = mapper.writeValueAsString(DecisionUsage(inputTokens = 10, outputTokens = 5))

        assertEquals(mapper.readTree("""{"inputTokens":10,"outputTokens":5}"""), mapper.readTree(json))
        assertEquals("{}", mapper.writeValueAsString(DecisionUsage()))
    }

    @Test
    fun `usage JSON reads back to an equal value`() {
        val usage = DecisionUsage(1, 2, 3, 4, 5, 6)

        assertEquals(usage, mapper.readValue(mapper.writeValueAsString(usage), DecisionUsage::class.java))
    }
}
