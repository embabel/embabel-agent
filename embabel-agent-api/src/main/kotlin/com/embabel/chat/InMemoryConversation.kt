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
package com.embabel.chat

import com.embabel.common.core.MobyNameGenerator

data class InMemoryConversation @JvmOverloads constructor(
    override val messages: List<Message> = emptyList(),
    override val id: String = MobyNameGenerator.generateName(),
    private val persistent: Boolean = false,
) : Conversation {

    override fun withMessage(message: Message): Conversation {
        return copy(
            messages = messages + message,
        )
    }

    override fun persistent(): Boolean = persistent

    companion object {

        @JvmStatic
        fun withSystemMessage(systemMessage: String): Conversation {
            return InMemoryConversation().withMessage(
                SystemMessage(
                    content = systemMessage,
                )
            )
        }
    }
}
