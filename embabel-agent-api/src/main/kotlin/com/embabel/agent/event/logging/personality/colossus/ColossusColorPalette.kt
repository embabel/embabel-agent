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
package com.embabel.agent.event.logging.personality.colossus

import com.embabel.agent.event.logging.personality.ColorPalette
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("colossus")
object ColossusColorPalette : ColorPalette {
    const val CAVE_SHADOW: Int = 0x2d2d30
    const val BRONZE_FIRE: Int = 0xcd7f32
    const val ANCIENT_STONE: Int = 0x8b7d6b
    const val TITAN_GOLD: Int = 0xffd700
    const val PANEL: Int = 0x84a396
    const val ACCENT_GREEN: Int = 0xacb366

    override val highlight: Int
        get() = TITAN_GOLD
    override val color2: Int
        get() = BRONZE_FIRE
}
