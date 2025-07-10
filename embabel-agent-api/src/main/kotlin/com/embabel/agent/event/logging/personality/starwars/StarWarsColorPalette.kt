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
package com.embabel.agent.event.logging.personality.starwars

import com.embabel.agent.event.logging.personality.ColorPalette
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("starwars")
object StarWarsColorPalette : ColorPalette {
    const val LIGHTSABER_BLUE: Int = 0x00bfff
    const val LIGHTSABER_GREEN: Int = 0x00ff00
    const val LIGHTSABER_RED: Int = 0xff0000
    const val IMPERIAL_GRAY: Int = 0x2f4f4f
    const val REPUBLIC_GOLD: Int = 0xffd700
    const val YELLOW_ACCENT: Int = 0xFFD100
    const val TATOOINE_ORANGE: Int = 0xAD7D37

    override val highlight: Int
        get() = LIGHTSABER_BLUE
    override val color2: Int
        get() = LIGHTSABER_GREEN
}
