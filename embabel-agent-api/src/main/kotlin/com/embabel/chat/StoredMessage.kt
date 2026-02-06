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
package com.embabel.chat

import com.embabel.common.core.types.Timestamped

/**
 * Role of the message sender.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * Minimal interface for messages that can be stored.
 * This is the contract between agent-api and chat-store.
 *
 * Rich message types like [Message] implement this interface,
 * allowing them to be persisted without the storage layer
 * needing to understand agent-specific features like assets or awaitables.
 */
interface StoredMessage : Timestamped {

    /**
     * Role of the message sender.
     */
    val role: MessageRole

    /**
     * Text content of the message.
     */
    val content: String
}
