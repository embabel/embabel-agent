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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

class DecisionCallTest {

    private val mapper = jacksonObjectMapper()

    private val spec = DecisionSpec.of(Questions.named("urgent").proposition("Is this urgent?").build())

    private val endUser = DecisionOption.of("acme", "end-user", String::class.java)

    private val json = JsonDecisionInput(mapper.readTree("""{"ticket":"printer on fire"}"""))

    @Test
    fun `kotlin block and java style chain give equal calls`() {
        val block = DecisionCall(json, spec) {
            option(endUser, "user-1")
            option(DecisionOptions.TIMEOUT, Duration.ofSeconds(5))
            requireNativeJson()
        }
        val chain = DecisionCall.of(json, spec)
            .withOption(endUser, "user-1")
            .withOption(DecisionOptions.TIMEOUT, Duration.ofSeconds(5))
            .requiringNativeJson()

        assertEquals(chain, block)
        assertEquals(chain.hashCode(), block.hashCode())
        assertEquals(DecisionCall.of(DecisionRequest.of("hello", spec)), DecisionCall(DecisionRequest.of("hello", spec)) {})
    }

    @Test
    fun `with methods leave the receiver unchanged`() {
        val original = DecisionCall.of(json, spec)

        val withOption = original.withOption(endUser, "user-1")
        val withInput = original.withInput(TextDecisionInput("other"))
        val native = original.requiringNativeJson()

        assertNull(original.option(endUser))
        assertTrue(original.optionKeys.isEmpty())
        assertEquals(json, original.input)
        assertFalse(original.nativeJsonRequired)
        assertEquals("user-1", withOption.option(endUser))
        assertEquals(TextDecisionInput("other"), withInput.input)
        assertTrue(native.nativeJsonRequired)
        assertNotEquals(original, withOption)
        assertNotEquals(original, withInput)
        assertNotEquals(original, native)
    }

    @Test
    fun `option keys cannot be modified`() {
        val call = DecisionCall.of(json, spec).withOption(endUser, "user-1")

        @Suppress("UNCHECKED_CAST")
        val keys = call.optionKeys as MutableSet<DecisionOption<*>>
        assertThrows<UnsupportedOperationException> { keys.clear() }
        assertEquals(setOf(endUser), call.optionKeys)
    }

    @Test
    fun `a value of the wrong type is rejected`() {
        @Suppress("UNCHECKED_CAST")
        val forced = endUser as DecisionOption<Any>
        val call = DecisionCall.of(json, spec)

        assertThrows<IllegalArgumentException> { call.withOption(forced, 42) }
        assertThrows<IllegalArgumentException> { DecisionCall(json, spec) { option(forced, 42) } }
    }

    @Test
    fun `setting a key twice keeps the last value`() {
        val call = DecisionCall.of(json, spec)
            .withOption(endUser, "first")
            .withOption(endUser, "second")

        assertEquals("second", call.option(endUser))
        assertEquals(setOf(endUser), call.optionKeys)
        assertEquals(DecisionCall.of(json, spec).withOption(endUser, "second"), call)
    }

    @Test
    fun `native json cannot be required for a text input`() {
        val call = DecisionCall.of(TextDecisionInput("hello"), spec)

        assertThrows<IllegalArgumentException> { call.requiringNativeJson() }
        assertThrows<IllegalArgumentException> { DecisionCall(TextDecisionInput("hello"), spec) { requireNativeJson() } }
    }

    @Test
    fun `swapping in a text input is rejected while native json is required`() {
        val native = DecisionCall.of(json, spec).requiringNativeJson()

        assertThrows<IllegalArgumentException> { native.withInput(TextDecisionInput("hello")) }
        assertTrue(native.withInput(JsonDecisionInput(mapper.readTree("[1]"))).nativeJsonRequired)
    }

    @Test
    fun `toString shows kind question names and option ids but no values or input`() {
        val call = DecisionCall.of(TextDecisionInput("PRIVATE-INPUT-9"), spec)
            .withOption(endUser, "SECRET-VALUE-7")

        val text = call.toString()

        assertFalse(text.contains("SECRET-VALUE-7"), text)
        assertFalse(text.contains("PRIVATE-INPUT-9"), text)
        assertTrue(text.contains("text"), text)
        assertTrue(text.contains("urgent"), text)
        assertTrue(text.contains("acme.end-user"), text)
    }

    @Test
    fun `toString leaves out json input content`() {
        val text = DecisionCall.of(json, spec).toString()

        assertFalse(text.contains("printer on fire"), text)
        assertTrue(text.contains("json"), text)
    }

    @Test
    fun `a call built from a request reads its input as text`() {
        val call = DecisionCall.of(DecisionRequest.of("hello", spec))

        assertEquals(TextDecisionInput("hello"), call.input)
        assertEquals(spec, call.spec)
        assertTrue(call.optionKeys.isEmpty())
        assertFalse(call.nativeJsonRequired)
    }
}
