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

import com.embabel.chat.Asset
import com.embabel.chat.AssetTracker

/**
 * Simple in-memory implementation of [AssetTracker] for testing and ephemeral use cases.
 */
class InMemoryAssetTracker(
    initialAssets: Collection<Asset> = emptyList(),
) : AssetTracker {

    private val _assets: MutableSet<Asset> = initialAssets.toMutableSet()

    override fun addAsset(asset: Asset) {
        if (_assets.any { it.id == asset.id }) {
            return
        }
        _assets += asset
    }

    override val assets: List<Asset>
        get() = _assets.toList()
}
