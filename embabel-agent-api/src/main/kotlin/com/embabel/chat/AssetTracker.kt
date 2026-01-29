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

import com.embabel.agent.api.tool.Tool
import com.embabel.chat.support.AssetAddingTool
import java.util.function.Predicate

/**
 * Extended by anything that can track assets
 */
interface AssetTracker : AssetView {

    /**
     * Add an asset to be tracked.
     * If the asset is already being tracked, do nothing.
     */
    fun addAsset(asset: Asset)

    /**
     * Wrap a tool so any outputs are tracked as assets.
     */
    fun addReturnedAssets(tool: Tool): Tool {
        return AssetAddingTool(
            delegate = tool,
            assetTracker = this,
            converter = { it },
            clazz = Asset::class.java
        )
    }

    /**
     * Wrap a tool so any outputs are tracked as assets, with a filter (Java-friendly).
     * Only assets that pass the filter will be tracked.
     * @param tool The tool to wrap
     * @param filter Predicate - only assets passing this filter are tracked
     */
    fun addReturnedAssets(tool: Tool, filter: Predicate<Asset>): Tool {
        return AssetAddingTool(
            delegate = tool,
            assetTracker = this,
            converter = { it },
            clazz = Asset::class.java,
            artifactFilter = { filter.test(it) }
        )
    }

    /**
     * Make these tools track any assets produced.
     */
    fun addAnyReturnedAssets(tools: List<Tool>): List<Tool> {
        return tools.map { addReturnedAssets(it) }
    }

    /**
     * Make these tools track any assets produced, with a filter.
     * Only assets that pass the filter will be tracked.
     * @param tools The tools to wrap
     * @param filter Predicate - only assets passing this filter are tracked
     */
    fun addAnyReturnedAssets(tools: List<Tool>, filter: Predicate<Asset>): List<Tool> {
        return tools.map { addReturnedAssets(it, filter) }
    }
}
