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
package com.embabel.chat.support.console

import com.embabel.agent.api.channel.*
import com.embabel.agent.spi.logging.ColorPalette
import com.embabel.agent.spi.logging.DefaultColorPalette
import com.embabel.chat.Message
import com.embabel.common.util.color
import org.apache.commons.text.WordUtils

class ConsoleOutputChannel(
    private val colorPalette: ColorPalette = DefaultColorPalette(),
) : OutputChannel {

    override fun send(event: OutputChannelEvent) {
        when (event) {
            is MessageOutputChannelEvent -> {
                val formattedResponse = WordUtils.wrap(
                    "${event.message.role.displayName}: ${event.message.content.color(colorPalette.color2)}",
                    140,
                )
                println(formattedResponse)
            }

            is ContentOutputChannelEvent -> {
                println("Content event: ${event.content}")
            }

            is ProgressOutputChannelEvent -> {
                println("▶ ${event.message}")
            }

            is LoggingOutputChannelEvent -> {
                println("🪵 ${event.message}")
            }

            else -> {
                println(event.toString())
            }
        }
    }
}
