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
package com.embabel.agent.spi.logging.personality

import com.embabel.common.util.bold
import com.embabel.common.util.color
import com.embabel.common.util.italic
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FormatUtilsTest {

    @Nested
    inner class CharacterFormatting {

        @Test
        fun `formats nonblank name and text exactly`() {
            val color = 0xbeb780

            val result = character(name = "Kier", text = "hello", color = color)

            assertEquals("${"Kier".bold()}: ${"hello".italic().color(color)}", result)
        }

        @Test
        fun `omits name prefix when name is blank`() {
            val color = 0xbeb780

            val result = character(name = "", text = "hello", color = color)

            assertEquals("hello".italic().color(color), result)
        }

        @Test
        fun `omits name prefix when name is whitespace only`() {
            val color = 0x00ff66

            val result = character(name = "   ", text = "whisper", color = color)

            assertEquals("whisper".italic().color(color), result)
        }

        @Test
        fun `preserves empty text body`() {
            val color = 0x00ff66

            val result = character(name = "Guide", text = "", color = color)

            assertEquals("${"Guide".bold()}: ${"".italic().color(color)}", result)
        }
    }
}
