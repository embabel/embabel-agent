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
package com.embabel.chat.spi

import com.embabel.chat.AssetTracker
import com.embabel.chat.DurableAsset
import com.embabel.chat.MaterializableAsset
import java.io.InputStream

/**
 * Application-supplied SPI for storing and resolving durable asset content.
 *
 * Implementations may use a filesystem, object storage, database, or another blob store.
 * Conversation stores should persist [DurableAsset] metadata, not the content bytes.
 * Unlike [AssetTracker], which tracks assets in a live conversation, an [AssetStore]
 * owns content beyond the lifetime of the process that created it.
 *
 * Durable asset tracking is opt-in: callers must supply a configured store when
 * wrapping tools that produce materializable assets.
 */
interface AssetStore {

    /**
     * Copy [asset] content to durable storage before its original location expires.
     * The implementation derives [DurableAsset.sizeBytes] and
     * [DurableAsset.contentHash] while copying the stream.
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
