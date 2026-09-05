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
import java.io.InputStream
import java.time.Instant

/**
 * An [Asset] whose content can be copied to durable storage.
 *
 * Implementations own the returned stream and callers must close it.
 */
interface MaterializableAsset : Asset {

    val name: String

    val mimeType: String?

    val sizeBytes: Long

    fun openStream(): InputStream
}

/**
 * Durable metadata for a materialized asset.
 *
 * [storageUri] is opaque to consumers. It is interpreted only by the [AssetStore]
 * that created this reference.
 */
data class DurableAsset(
    override val id: String,
    val name: String,
    val mimeType: String?,
    val sizeBytes: Long,
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

/**
 * Stores and resolves durable asset content.
 *
 * Implementations may use a filesystem, object storage, or another blob store.
 * Conversation stores should persist [DurableAsset] metadata, not the content bytes.
 */
interface AssetStore {

    /**
     * Copy [asset] content to durable storage before its original location expires.
     */
    fun store(asset: MaterializableAsset): DurableAsset

    /**
     * Open the content represented by [asset]. The caller must close the returned stream.
     */
    fun open(asset: DurableAsset): InputStream

    /**
     * Delete content represented by [asset]. Retention policy is implementation-specific.
     */
    fun delete(asset: DurableAsset)
}
