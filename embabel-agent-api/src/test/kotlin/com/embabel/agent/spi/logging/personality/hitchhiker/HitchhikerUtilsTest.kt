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
package com.embabel.agent.spi.logging.personality.hitchhiker

import com.embabel.common.util.bold
import com.embabel.common.util.color
import com.embabel.common.util.italic
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Verifies [guide] styling, including empty text. */
class HitchhikerUtilsTest {

    @Nested
    inner class GuideSaying {

        @Test
        fun `formats Guide saying exactly`() {
            val result = guide("Don't Panic")

            assertEquals(
                "📕 ${"Guide".bold()} ${"Don't Panic".italic().color(HitchhikerColorPalette.BABEL_GREEN)}",
                result,
            )
        }

        @Test
        fun `handles empty text`() {
            val result = guide("")

            assertEquals(
                "📕 ${"Guide".bold()} ${"".italic().color(HitchhikerColorPalette.BABEL_GREEN)}",
                result,
            )
        }
    }
}
