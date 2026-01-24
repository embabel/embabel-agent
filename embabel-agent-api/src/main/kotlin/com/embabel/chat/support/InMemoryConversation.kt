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
package com.embabel.chat.support

import com.embabel.chat.AssetTracker
import com.embabel.chat.Conversation
import com.embabel.chat.Message
import com.embabel.common.core.MobyNameGenerator

/**
 * Simple in-memory implementation of [Conversation] for testing and ephemeral use cases.
 */
class InMemoryConversation private constructor(
    override val id: String = MobyNameGenerator.generateName(),
    private val persistent: Boolean = false,
    private val _messages: MutableList<Message> = mutableListOf(),
    override val assetTracker: AssetTracker = InMemoryAssetTracker(),
) : Conversation {

    @JvmOverloads
    constructor(
        messages: List<Message> = emptyList(),
        id: String = MobyNameGenerator.generateName(),
        persistent: Boolean = false,
        assets: AssetTracker = InMemoryAssetTracker(),
    ) : this(
        id = id,
        persistent = persistent,
        _messages = messages.toMutableList(),
        assetTracker = assets,
    )

    override fun addMessage(message: Message): Message {
        _messages += message
        return message
    }

    override val messages: List<Message>
        get() = _messages.toList()

    override fun persistent(): Boolean = persistent

    override fun last(n: Int): Conversation =
        InMemoryConversation(
            id = this.id,
            persistent = false,
            _messages = this._messages.takeLast(n).toMutableList(),
            assetTracker = this.assetTracker,
        )
}
