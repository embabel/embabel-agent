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

/**
 * Represents the author of a message in a conversation.
 *
 * This interface provides a persistence-agnostic abstraction for user identity
 * in chat contexts. Implementations can extend this interface with additional
 * persistence-specific annotations.
 *
 * ## Usage
 *
 * For simple cases, use the provided [SimpleMessageAuthor] data class:
 * ```kotlin
 * val author = SimpleMessageAuthor(id = "user-123", displayName = "Alice")
 * conversation.addMessageFrom(message, author)
 * ```
 */
interface MessageAuthor {
    /**
     * Unique identifier for the author.
     */
    val id: String

    /**
     * Human-readable display name for the author.
     */
    val displayName: String
}

/**
 * Simple implementation of [MessageAuthor] for non-persistent use cases.
 */
data class SimpleMessageAuthor(
    override val id: String,
    override val displayName: String
) : MessageAuthor
