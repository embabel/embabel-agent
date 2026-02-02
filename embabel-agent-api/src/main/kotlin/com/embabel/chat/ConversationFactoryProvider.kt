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
 * Provider for [ConversationFactory] instances by type.
 *
 * Implementations resolve factories based on [ConversationStoreType],
 * typically backed by Spring beans registered via autoconfiguration.
 *
 * To use conversation factories, either:
 * - Inject [ConversationFactoryProvider] directly via Spring DI
 * - Use the fluent API: `context.ai().conversationFactory(ConversationStoreType.STORED)`
 *
 * Note: The fluent API requires `embabel-chat-store` on the classpath.
 * Without it, calling those methods will throw [org.springframework.beans.factory.NoSuchBeanDefinitionException].
 */
interface ConversationFactoryProvider {

    /**
     * Get a conversation factory for the given store type.
     *
     * @param type the conversation store type
     * @return the factory for that type
     * @throws IllegalArgumentException if no factory is registered for the type
     */
    fun getFactory(type: ConversationStoreType): ConversationFactory

    /**
     * Get a conversation factory for the given store type, or null if not available.
     *
     * @param type the conversation store type
     * @return the factory for that type, or null
     */
    fun getFactoryOrNull(type: ConversationStoreType): ConversationFactory?

    /**
     * Get all registered factory types.
     */
    fun availableTypes(): Set<ConversationStoreType>
}

/**
 * Simple map-based implementation of [ConversationFactoryProvider].
 */
class MapConversationFactoryProvider(
    private val factories: Map<ConversationStoreType, ConversationFactory>
) : ConversationFactoryProvider {

    constructor(vararg factories: ConversationFactory) : this(
        factories.associateBy { it.storeType }
    )

    constructor(factories: List<ConversationFactory>) : this(
        factories.associateBy { it.storeType }
    )

    override fun getFactory(type: ConversationStoreType): ConversationFactory {
        return factories[type]
            ?: throw IllegalArgumentException(
                "No ConversationFactory registered for type $type. Available: ${factories.keys}"
            )
    }

    override fun getFactoryOrNull(type: ConversationStoreType): ConversationFactory? {
        return factories[type]
    }

    override fun availableTypes(): Set<ConversationStoreType> = factories.keys
}
