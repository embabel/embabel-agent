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
package com.embabel.common.byok

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BlankApiKeyTest {

    @Test
    fun `an empty key is rejected as absent`() {
        val e = assertThrows(InvalidApiKeyException::class.java) { requireUsableApiKey("") }
        assertEquals(BLANK_API_KEY_MESSAGE, e.message)
    }

    @Test
    fun `a whitespace-only key is rejected as absent`() {
        listOf(" ", "   ", "\t", "\n").forEach { key ->
            assertThrows(InvalidApiKeyException::class.java) { requireUsableApiKey(key) }
        }
    }

    @Test
    fun `a null key is rejected as absent`() {
        assertThrows(InvalidApiKeyException::class.java) { requireUsableApiKey(null) }
    }

    @Test
    fun `a real key passes`() {
        assertDoesNotThrow { requireUsableApiKey("sk-not-a-real-key") }
    }

    @Test
    fun `surrounding whitespace does not make a real key blank`() {
        assertDoesNotThrow { requireUsableApiKey("  sk-not-a-real-key  ") }
    }
}
