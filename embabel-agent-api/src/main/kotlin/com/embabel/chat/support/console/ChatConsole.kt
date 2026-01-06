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

import com.embabel.agent.spi.logging.ColorPalette
import com.embabel.agent.spi.logging.DefaultColorPalette
import com.embabel.chat.ChatSession
import com.embabel.chat.UserMessage
import com.embabel.common.util.color

/**
 * Simple support for console chat.
 */
class ChatConsole {

    @JvmOverloads
    fun chat(
        chatSession: ChatSession,
        welcome: String? = null,
        colorPalette: ColorPalette = DefaultColorPalette(),
    ): String {
        // Print welcome message
        println(
            (welcome?.let { it + "\n" } ?: "") +
                    """
        Chat session ${chatSession.conversation.id} started. Type 'exit' to end the session.
        Type /help for available commands.
        """.trimIndent().color(colorPalette.highlight)
        )

        while (true) {
            print("You: ".color(colorPalette.highlight))
            val userInput = readln()

            if (userInput.equals("exit", ignoreCase = true)) {
                break
            }

            val userMessage = UserMessage(userInput)
            chatSession.onUserMessage(userMessage)
        }

        return "Conversation finished"
    }
}
