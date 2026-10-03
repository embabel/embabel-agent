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
package com.embabel.agent.api.tool.hitl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolArgumentsCanonicalizerTest {

    private fun canon(s: String) = ToolArgumentsCanonicalizer.canonicalize(s)

    @Test
    fun `sorts keys and strips whitespace`() {
        assertEquals("""{"a":1,"b":"x"}""", canon("""{ "b" : "x",  "a": 1 }"""))
    }

    @Test
    fun `sorts nested object keys but keeps array order`() {
        assertEquals(
            """{"items":[2,1],"meta":{"x":1,"y":2}}""",
            canon("""{"meta":{"y":2,"x":1},"items":[2,1]}"""),
        )
    }

    @Test
    fun `drops explicit nulls`() {
        assertEquals(canon("""{"title":"t"}"""), canon("""{"title":"t","priority":null}"""))
    }

    @Test
    fun `treats integral floats as integers`() {
        assertEquals(canon("""{"n":1}"""), canon("""{"n":1.0}"""))
        assertEquals("""{"n":100}""", canon("""{"n":1e2}"""))
        assertEquals("""{"n":1.5}""", canon("""{"n":1.50}"""))
    }

    @Test
    fun `different values stay different`() {
        assertNotEquals(canon("""{"n":1}"""), canon("""{"n":2}"""))
        assertNotEquals(canon("""{"a":[1,2]}"""), canon("""{"a":[2,1]}"""))
    }

    @Test
    fun `non JSON input is trimmed`() {
        assertEquals("hello", canon("  hello \n"))
    }

    @Test
    fun `blank input canonicalizes to empty object`() {
        assertEquals("{}", canon(""))
        assertEquals("{}", canon("   "))
    }

    private fun assertNotEquals(a: String, b: String) = org.junit.jupiter.api.Assertions.assertNotEquals(a, b)
}
