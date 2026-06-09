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
package com.embabel.common.ai.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NativeStructuredOutputModeTest {

    @Nested
    inner class ExtensionTests {

        @Test
        fun `get native structured output returns null when not configured`() {
            val options = LlmOptions()

            assertNull(options.getNativeStructuredOutput())
        }

        @Test
        fun `with native structured output stores mode in extensions`() {
            val options = LlmOptions().withNativeStructuredOutput(NativeStructuredOutputMode.ENABLED)

            assertEquals(NativeStructuredOutputMode.ENABLED, options.getNativeStructuredOutput())
        }

        @Test
        fun `with native structured output can be updated`() {
            val options = LlmOptions()
                .withNativeStructuredOutput(NativeStructuredOutputMode.ENABLED)
                .withNativeStructuredOutput(NativeStructuredOutputMode.DISABLED)

            assertEquals(NativeStructuredOutputMode.DISABLED, options.getNativeStructuredOutput())
        }
    }
}
