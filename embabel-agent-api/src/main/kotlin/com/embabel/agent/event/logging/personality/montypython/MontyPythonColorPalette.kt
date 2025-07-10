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
package com.embabel.agent.event.logging.personality.montypython

import com.embabel.agent.event.logging.personality.ColorPalette
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("montypython")
object MontyPythonColorPalette : ColorPalette {
    const val HOLY_GRAIL_GOLD: Int = 0xffd700
    const val SPAM_PINK: Int = 0xffc0cb
    const val KNIGHT_ARMOR: Int = 0xc0c0c0
    const val DEAD_PARROT_BLUE: Int = 0x4169e1
    const val SILLY_WALK_BROWN: Int = 0x8b4513
    const val BRIGHT_RED: Int = 0xff0000

    override val highlight: Int
        get() = HOLY_GRAIL_GOLD
    override val color2: Int
        get() = DEAD_PARROT_BLUE
}
