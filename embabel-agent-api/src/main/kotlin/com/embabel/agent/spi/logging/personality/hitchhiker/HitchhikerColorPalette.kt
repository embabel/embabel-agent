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
package com.embabel.agent.spi.logging.personality.hitchhiker

import com.embabel.agent.spi.logging.ColorPalette
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    name = ["embabel.agent.platform.logging.personality"],
    havingValue = "hitchhiker"
)
object HitchhikerColorPalette : ColorPalette {
    const val BABEL_GREEN: Int = 0x00ff66 // Guide text green
    const val TOWEL_YELLOW: Int = 0xffe066
    const val DEEP_SPACE_BLUE: Int = 0x003366
    const val PANIC_RED: Int = 0xff0055

    override val highlight: Int
        get() = BABEL_GREEN
    override val color2: Int
        get() = TOWEL_YELLOW
}
