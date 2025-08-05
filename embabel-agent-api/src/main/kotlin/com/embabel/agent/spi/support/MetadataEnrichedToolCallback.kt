/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.spi.support

import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.common.util.loggerFor
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Add metadata about the tool group to which this tool belongs.
 */
class MetadataEnrichedToolCallback(
    val toolGroupMetadata: ToolGroupMetadata?,
    private val delegate: ToolCallback,
) : ToolCallback {

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun call(toolInput: String): String {
        try {
            return delegate.call(toolInput)
        } catch (t: Throwable) {
            loggerFor<MetadataEnrichedToolCallback>().warn(
                "Tool call failure on ${delegate.toolDefinition.name()}: {}",
                t.message,
            )
            throw t
        }
    }

    override fun toString(): String {
        return "MetadataEnrichedToolCallback(toolGroupMetadata=$toolGroupMetadata, delegate=${delegate.toolDefinition.name()})"
    }
}
