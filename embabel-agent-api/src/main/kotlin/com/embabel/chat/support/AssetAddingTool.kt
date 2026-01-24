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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.support.DelegatingTool
import com.embabel.chat.Asset
import com.embabel.chat.AssetTracker
import org.slf4j.LoggerFactory

/**
 * Add the result of calling this tool as an asset
 * to the given AssetTracker if it is of the expected type.
 * Also unwraps Iterables of the expected type.
 * @param T The type of artifact to convert to an Asset
 * @param delegate The tool to delegate to
 * @param assetTracker The asset tracker to add assets to
 * @param converter Function to convert from T to Asset
 * @param clazz The class of T
 */
class AssetAddingTool<T>(
    override val delegate: Tool,
    val assetTracker: AssetTracker,
    val converter: (T) -> Asset,
    val clazz: Class<T>,
) : DelegatingTool {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun call(input: String): Tool.Result {
        val result = delegate.call(input)
        when (result) {
            is Tool.Result.WithArtifact -> {
                val artifact = result.artifact
                when {
                    // Handle Iterable<T> - unwrap and add all items
                    artifact is Iterable<*> -> {
                        artifact.filterIsInstance(clazz).forEach { item ->
                            addAsset(item)
                        }
                    }
                    // Handle single item of type T
                    clazz.isInstance(artifact) -> {
                        @Suppress("UNCHECKED_CAST")
                        addAsset(artifact as T)
                    }
                }
            }

            else -> {
                // No artifact to track
            }
        }
        return result
    }

    private fun addAsset(item: T) {
        val asset = converter(item)
        assetTracker.addAsset(asset)
        logger.info(
            "Added asset of class {} with id={} from tool={}",
            asset.javaClass.name,
            asset.id,
            definition.name,
        )
    }

    override val definition: Tool.Definition = delegate.definition

}
