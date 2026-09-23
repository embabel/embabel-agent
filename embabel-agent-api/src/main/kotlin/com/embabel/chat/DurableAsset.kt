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

import com.embabel.agent.api.reference.LlmReference
import com.embabel.common.ai.media.MimeTypes
import java.io.InputStream
import java.time.Instant

/**
 * An [Asset] whose content can be copied to durable storage.
 *
 * Implementations own the returned stream and callers must close it.
 */
interface MaterializableAsset : Asset {

    val name: String

    /**
     * MIME type of the content, preferably one of the values defined by [MimeTypes].
     * Null when the producer cannot determine it.
     */
    val mimeType: String?

    fun openStream(): InputStream
}

/**
 * Durable metadata for a materialized asset.
 *
 * [storageUri] is opaque to consumers. It is interpreted only by the
 * [com.embabel.chat.spi.AssetStore] that created this reference.
 */
data class DurableAsset(
    override val id: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
    /**
     * Hash computed by the store during materialization. It can be used to verify
     * content integrity when the asset is retrieved.
     */
    val contentHash: String,
    val storageUri: String,
    override val timestamp: Instant = Instant.now(),
) : Asset {

    override fun persistent(): Boolean = true

    override fun reference(): LlmReference = LlmReference.of(
        name = name,
        description = "Durably stored asset $name",
        tools = emptyList(),
        notes = "MIME type: ${mimeType ?: "unknown"}; size: $sizeBytes bytes",
    )
}
