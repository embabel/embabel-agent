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

import com.embabel.agent.api.common.reference.LlmReferenceProvider
import com.embabel.common.core.types.Timestamped
import java.time.Instant
import java.util.*

/**
 * Asset associated with a conversation.
 * Immutable.
 */
interface Asset : LlmReferenceProvider, Timestamped {

    /**
     * Unique asset id
     */
    val id: String

    companion object {

        @JvmStatic
        fun asAsset(provider: LlmReferenceProvider): Asset {
            return LlmReferenceProviderAsset(
                provider = provider,
                id = UUID.randomUUID().toString(),
            )
        }

    }

}


private class LlmReferenceProviderAsset(
    private val provider: LlmReferenceProvider,
    override val id: String,
    override val timestamp: Instant = Instant.now(),
) : Asset, LlmReferenceProvider by provider
