/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.event.logging.personality.severance

import com.embabel.agent.event.logging.personality.ColorPalette
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("severance")
object LumonColorPalette : ColorPalette {
    const val MEMBRANE: Int = 0xbeb780
    const val WELLNESS: Int = 0xf5f5dc
    const val MDR: Int = 0x00cc66
    const val ORIGINAL_GREEN: Int = 0x7da17e
    const val DISCIPLINE: Int = 0x2f4f4f

    override val highlight: Int
        get() = MEMBRANE
    override val color2: Int
        get() = MDR
}
