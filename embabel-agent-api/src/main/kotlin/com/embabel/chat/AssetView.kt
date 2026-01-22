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

import com.embabel.agent.api.common.LlmReference
import java.time.Instant

/**
 * View of tracked assets
 */
interface AssetView {

    /**
     * All tracked assets, in order in which they were added
     */
    val assets: List<Asset>

    /**
     * Convenience method to return references
     */
    fun references(): List<LlmReference> =
        assets.map { it.reference() }

    /**
     * Assets timestamped since the given instant
     */
    fun since(instant: Instant): AssetView =
        AssetViewImpl(
            assets = assets.filter { it.timestamp.toEpochMilli() >= instant.toEpochMilli() }
        )

    /**
     * The most recently timestamped assets
     */
    fun mostRecent(n: Int): AssetView =
        AssetViewImpl(
            assets = assets.sortedBy { it.timestamp }
                .takeLast(n)
        )

    /**
     * The most recently added assets
     */
    fun mostRecentlyAdded(n: Int): AssetView =
        AssetViewImpl(
            assets = assets.takeLast(n)
        )

    companion object {

        @JvmStatic
        fun of(assets: List<Asset>): AssetView =
            AssetViewImpl(assets)
    }

}

class AssetViewImpl(
    override val assets: List<Asset>
) : AssetView
