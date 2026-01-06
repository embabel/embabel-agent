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
package com.embabel.agent.discord

import com.embabel.common.util.loggerFor
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion

object DiscordMessageUtils {
    // It's really 2000 but we don't want breakage
    private const val DISCORD_MESSAGE_LIMIT = 1900
    private const val CODE_BLOCK_MARKER = "```"
    private const val CODE_BLOCK_OVERHEAD = 8 // Space for opening/closing ``` + language

    data class CodeBlockState(
        val inCodeBlock: Boolean = false,
        val language: String = "",
    )

    fun splitMessage(content: String): List<String> {
        if (content.length <= DISCORD_MESSAGE_LIMIT) {
            return listOf(content)
        }

        val messages = mutableListOf<String>()
        var currentMessage = StringBuilder()
        val lines = content.split('\n')
        var codeBlockState = CodeBlockState()

        for (line in lines) {
            val newCodeBlockState = updateCodeBlockState(codeBlockState, line)
            val proposedLength = currentMessage.length + line.length + 1

            // Check if adding this line would exceed the limit
            if (proposedLength > DISCORD_MESSAGE_LIMIT && currentMessage.isNotEmpty()) {
                // If we're in a code block, we need to close it properly
                if (codeBlockState.inCodeBlock) {
                    currentMessage.append('\n').append(CODE_BLOCK_MARKER)
                }

                messages.add(currentMessage.toString().trimEnd())
                currentMessage = StringBuilder()

                // If we were in a code block, reopen it in the new message
                if (codeBlockState.inCodeBlock) {
                    currentMessage.append(CODE_BLOCK_MARKER)
                    if (codeBlockState.language.isNotEmpty()) {
                        currentMessage.append(codeBlockState.language)
                    }
                    currentMessage.append('\n')
                }
            }

            // Handle extremely long single lines
            if (line.length > DISCORD_MESSAGE_LIMIT) {
                if (currentMessage.isNotEmpty()) {
                    if (codeBlockState.inCodeBlock) {
                        currentMessage.append('\n').append(CODE_BLOCK_MARKER)
                    }
                    messages.add(currentMessage.toString().trimEnd())
                    currentMessage = StringBuilder()
                }

                // Split the long line, preserving code block context if needed
                var remainingLine = line
                var isFirstChunk = true

                while (remainingLine.length > DISCORD_MESSAGE_LIMIT) {
                    val availableSpace = if (codeBlockState.inCodeBlock && isFirstChunk) {
                        DISCORD_MESSAGE_LIMIT - CODE_BLOCK_OVERHEAD - codeBlockState.language.length
                    } else if (codeBlockState.inCodeBlock) {
                        DISCORD_MESSAGE_LIMIT - (2 * CODE_BLOCK_OVERHEAD) - codeBlockState.language.length
                    } else {
                        DISCORD_MESSAGE_LIMIT
                    }

                    val chunkSize = maxOf(1, availableSpace)
                    val chunk = remainingLine.substring(0, minOf(chunkSize, remainingLine.length))

                    val chunkMessage = StringBuilder()
                    if (codeBlockState.inCodeBlock) {
                        chunkMessage.append(CODE_BLOCK_MARKER)
                        if (codeBlockState.language.isNotEmpty()) {
                            chunkMessage.append(codeBlockState.language)
                        }
                        chunkMessage.append('\n')
                    }

                    chunkMessage.append(chunk)

                    if (codeBlockState.inCodeBlock) {
                        chunkMessage.append('\n').append(CODE_BLOCK_MARKER)
                    }

                    messages.add(chunkMessage.toString())
                    remainingLine = remainingLine.substring(chunk.length)
                    isFirstChunk = false
                }

                if (remainingLine.isNotEmpty()) {
                    if (codeBlockState.inCodeBlock) {
                        currentMessage.append(CODE_BLOCK_MARKER)
                        if (codeBlockState.language.isNotEmpty()) {
                            currentMessage.append(codeBlockState.language)
                        }
                        currentMessage.append('\n')
                    }
                    currentMessage.append(remainingLine).append('\n')
                }
            } else {
                if (currentMessage.isNotEmpty()) {
                    currentMessage.append('\n')
                }
                currentMessage.append(line)
            }

            codeBlockState = newCodeBlockState
        }

        if (currentMessage.isNotEmpty()) {
            messages.add(currentMessage.toString().trimEnd())
        }

        return messages
    }

    private fun updateCodeBlockState(
        currentState: CodeBlockState,
        line: String,
    ): CodeBlockState {
        val trimmedLine = line.trim()

        if (trimmedLine.startsWith(CODE_BLOCK_MARKER)) {
            return if (currentState.inCodeBlock) {
                // Closing a code block
                CodeBlockState(inCodeBlock = false, language = "")
            } else {
                // Opening a code block
                val language = trimmedLine.substring(3).trim()
                CodeBlockState(inCodeBlock = true, language = language)
            }
        }

        return currentState
    }

    fun sendLongMessage(
        channel: MessageChannelUnion,
        content: String,
    ) {
        try {
            val messageParts = splitMessage(content)
            messageParts.forEach { part ->
                channel.sendMessage(part).queue()
            }
        } catch (e: Exception) {
            loggerFor<DiscordMessageUtils>().warn("Failed to send message to Discord channel: {}", e.message)
            channel.sendMessage("Sorry, something went wrong, please try again").queue()
        }
    }
}
