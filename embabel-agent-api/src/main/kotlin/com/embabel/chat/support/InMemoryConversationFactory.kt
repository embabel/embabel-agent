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

import com.embabel.chat.Conversation
import com.embabel.chat.ConversationFactory
import com.embabel.chat.ConversationStoreType

/**
 * Factory for creating [InMemoryConversation] instances.
 *
 * Messages are stored in memory only and not persisted.
 * Suitable for testing and ephemeral sessions.
 */
class InMemoryConversationFactory : ConversationFactory {

    override val storeType: ConversationStoreType = ConversationStoreType.IN_MEMORY

    override fun create(id: String): Conversation = InMemoryConversation(id = id)
}
