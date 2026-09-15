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

import com.embabel.chat.AssetStore
import com.embabel.chat.DurableAsset
import com.embabel.chat.MaterializableAsset
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * Durable [AssetStore] that keeps asset content below [root].
 *
 * The storage URI is intentionally opaque and relative to this store's root, allowing
 * the root directory to move without invalidating persisted [DurableAsset] metadata.
 */
class FileSystemAssetStore(root: Path) : AssetStore {

    private val root = root.toAbsolutePath().normalize()

    init {
        Files.createDirectories(this.root)
    }

    override fun store(asset: MaterializableAsset): DurableAsset {
        val storageUri = UUID.randomUUID().toString()
        val target = pathFor(storageUri)
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)

        try {
            asset.openStream().use { source ->
                DigestInputStream(source, digest).use { content ->
                    Files.copy(content, target)
                }
            }
        } catch (ex: Exception) {
            Files.deleteIfExists(target)
            throw ex
        }

        return DurableAsset(
            id = asset.id,
            name = asset.name,
            mimeType = asset.mimeType,
            sizeBytes = Files.size(target),
            contentHash = HexFormat.of().formatHex(digest.digest()),
            storageUri = storageUri,
            timestamp = asset.timestamp,
        )
    }

    override fun open(asset: DurableAsset): InputStream = Files.newInputStream(pathFor(asset.storageUri))

    override fun delete(asset: DurableAsset) {
        Files.deleteIfExists(pathFor(asset.storageUri))
    }

    private fun pathFor(storageUri: String): Path {
        val path = root.resolve(storageUri).normalize()
        require(path.parent == root) { "Invalid asset storage URI" }
        return path
    }

    private companion object {
        const val HASH_ALGORITHM = "SHA-256"
    }
}
