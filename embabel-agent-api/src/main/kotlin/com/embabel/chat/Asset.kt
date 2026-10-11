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

import com.embabel.agent.api.reference.LlmReferenceProvider
import com.embabel.common.core.StableIdentified
import com.embabel.common.core.types.Timestamped
import java.time.Instant
import java.util.*

/**
 * Asset associated with a conversation.
 *
 * An asset represents a resource referenceable by the LLM (via [reference]) and
 * tracked across conversations. Assets may be produced out-of-band by tools or agents,
 * or supplied as inbound attachments on [UserMessage] and tracked via [AssetTracker].
 *
 * Content can be made durable across sessions by materializing via [MaterializableAsset]
 * into an [com.embabel.chat.spi.AssetStore], yielding a [DurableAsset].
 * For direct multimodal model inputs (such as image or document prompts), see [MediaPart].
 */
interface Asset : LlmReferenceProvider, StableIdentified, Timestamped {

    companion object {

        @JvmStatic
        fun asAsset(provider: LlmReferenceProvider): Asset {
            return LlmReferenceProviderAsset(
                provider = provider,
                id = UUID.randomUUID().toString(),
                persistent = false,
            )
        }

    }

}


private class LlmReferenceProviderAsset(
    private val provider: LlmReferenceProvider,
    override val id: String,
    private val persistent: Boolean,
    override val timestamp: Instant = Instant.now(),
) : Asset, LlmReferenceProvider by provider {

    override fun persistent(): Boolean = persistent
}
