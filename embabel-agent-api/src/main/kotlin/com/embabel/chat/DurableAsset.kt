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
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

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

    companion object {

        /**
         * Create a transient [MaterializableAsset] from a stream supplier.
         *
         * Assets created via stream suppliers cannot be serialized into JSON (e.g. for
         * blackboard checkpoints). To persist or checkpoint messages carrying stream-based
         * assets, materialize them into an [com.embabel.chat.spi.AssetStore] first and
         * attach the resulting [DurableAsset].
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            name: String,
            streamSupplier: () -> InputStream,
            mimeType: String? = null,
            id: String = UUID.randomUUID().toString(),
            timestamp: Instant = Instant.now(),
            description: String = "Asset $name",
        ): MaterializableAsset = StreamMaterializableAsset(
            name = name,
            mimeType = mimeType,
            streamSupplier = streamSupplier,
            id = id,
            timestamp = timestamp,
            description = description,
        )

        /**
         * Create a serializable [MaterializableAsset] from in-memory bytes.
         *
         * The supplied [data] byte array is defensively copied. Jackson serializes
         * [ByteArrayMaterializableAsset] directly (writing bytes as Base64), making it
         * safe for blackboard checkpointing and message serialization.
         */
        @JvmStatic
        @JvmOverloads
        fun fromBytes(
            name: String,
            data: ByteArray,
            mimeType: String? = null,
            id: String = UUID.randomUUID().toString(),
            timestamp: Instant = Instant.now(),
            description: String = "Asset $name",
        ): MaterializableAsset = ByteArrayMaterializableAsset(
            name = name,
            data = data.copyOf(),
            mimeType = mimeType,
            id = id,
            timestamp = timestamp,
            description = description,
        )

        /**
         * Create a serializable [MaterializableAsset] from a file system [Path].
         *
         * Returns a [PathMaterializableAsset] that round-trips via JSON. Note that the
         * referenced file path must remain accessible on disk when materialized or restored.
         */
        @JvmStatic
        @JvmOverloads
        fun fromPath(
            path: Path,
            name: String = path.fileName?.toString() ?: path.toString(),
            mimeType: String? = null,
            id: String = UUID.randomUUID().toString(),
            timestamp: Instant = Instant.now(),
            description: String = "Asset $name",
        ): MaterializableAsset = PathMaterializableAsset(
            path = path,
            name = name,
            mimeType = mimeType ?: Files.probeContentType(path),
            id = id,
            timestamp = timestamp,
            description = description,
        )
    }
}

/**
 * A [MaterializableAsset] holding in-memory bytes.
 *
 * Serializes directly into JSON (with the byte array encoded as Base64), making it safe
 * for process persistence and blackboard checkpointing.
 */
data class ByteArrayMaterializableAsset(
    override val name: String,
    val data: ByteArray,
    override val mimeType: String? = null,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    val description: String = "Asset $name",
) : MaterializableAsset {

    override fun persistent(): Boolean = false

    override fun openStream(): InputStream = ByteArrayInputStream(data)

    override fun reference(): LlmReference = LlmReference.of(
        name = name,
        description = description,
        tools = emptyList(),
        notes = "MIME type: ${mimeType ?: "unknown"}",
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteArrayMaterializableAsset) return false
        if (name != other.name) return false
        if (!data.contentEquals(other.data)) return false
        if (mimeType != other.mimeType) return false
        if (id != other.id) return false
        if (timestamp != other.timestamp) return false
        if (description != other.description) return false
        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + id.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + description.hashCode()
        return result
    }
}

/**
 * A [MaterializableAsset] backed by a file system [Path].
 *
 * Serializes the path location for JSON round-tripping. Note that the referenced file
 * must remain accessible on disk when the asset is materialized or restored.
 */
data class PathMaterializableAsset(
    val path: Path,
    override val name: String = path.fileName?.toString() ?: path.toString(),
    override val mimeType: String? = null,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Instant = Instant.now(),
    val description: String = "Asset $name",
) : MaterializableAsset {

    override fun persistent(): Boolean = false

    override fun openStream(): InputStream = Files.newInputStream(path)

    override fun reference(): LlmReference = LlmReference.of(
        name = name,
        description = description,
        tools = emptyList(),
        notes = "MIME type: ${mimeType ?: "unknown"}",
    )
}

private class StreamMaterializableAsset(
    override val name: String,
    override val mimeType: String?,
    private val streamSupplier: () -> InputStream,
    override val id: String,
    override val timestamp: Instant,
    private val description: String,
) : MaterializableAsset {

    override fun persistent(): Boolean = false

    override fun openStream(): InputStream = streamSupplier()

    override fun reference(): LlmReference = LlmReference.of(
        name = name,
        description = description,
        tools = emptyList(),
        notes = "MIME type: ${mimeType ?: "unknown"}",
    )
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
