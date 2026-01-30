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
 * An [AssetView] that merges assets from multiple views.
 *
 * Assets are combined in order: views provided first have their assets appear first.
 * Assets are deduplicated by ID - if the same asset ID appears in multiple views,
 * only the first occurrence is kept.
 *
 * @param views The AssetViews to merge, in order of priority
 */
class MergedAssetView(
    private val views: List<AssetView>,
) : AssetView {

    /**
     * Convenience constructor for two views.
     */
    constructor(first: AssetView, second: AssetView) : this(listOf(first, second))

    /**
     * Convenience constructor for vararg views.
     */
    constructor(vararg views: AssetView) : this(views.toList())

    override val assets: List<Asset>
        get() {
            val seen = mutableSetOf<String>()
            return views.flatMap { it.assets }
                .filter { asset ->
                    if (seen.contains(asset.id)) {
                        false
                    } else {
                        seen.add(asset.id)
                        true
                    }
                }
        }

    companion object {

        /**
         * Create a MergedAssetView from multiple views, filtering out nulls.
         */
        @JvmStatic
        fun of(vararg views: AssetView?): AssetView {
            val nonNullViews = views.filterNotNull()
            return when (nonNullViews.size) {
                0 -> AssetView.of(emptyList())
                1 -> nonNullViews[0]
                else -> MergedAssetView(nonNullViews)
            }
        }

        /**
         * Create a MergedAssetView from a list of views.
         */
        @JvmStatic
        fun of(views: List<AssetView>): AssetView {
            return when (views.size) {
                0 -> AssetView.of(emptyList())
                1 -> views[0]
                else -> MergedAssetView(views)
            }
        }
    }
}
