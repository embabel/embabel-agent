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
package com.embabel.chat.event

/**
 * Status of a message in its lifecycle.
 */
enum class MessageStatus {
    /**
     * Message has been added to the conversation.
     *
     * For in-memory conversations, this is the terminal state.
     * For persistent conversations, this may be followed by [PERSISTED] or [PERSISTENCE_FAILED].
     */
    ADDED,

    /**
     * Message has been successfully persisted to storage.
     *
     * Only fired by persistent conversation implementations.
     */
    PERSISTED,

    /**
     * Message persistence failed.
     *
     * Only fired by persistent conversation implementations.
     * Check [MessageEvent.error] for details.
     */
    PERSISTENCE_FAILED
}
