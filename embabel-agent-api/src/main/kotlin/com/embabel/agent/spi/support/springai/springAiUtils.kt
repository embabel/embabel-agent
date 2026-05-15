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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.Usage
import com.embabel.agent.core.support.safelyGetTools
import com.embabel.agent.core.support.safelyGetToolsFrom
import org.springframework.ai.tool.ToolCallback

/**
 * Extract tools and convert to Spring AI ToolCallbacks.
 * Internal use only - external code should use [safelyGetTools] and convert at the Spring AI boundary.
 */
internal fun safelyGetToolCallbacks(instances: Collection<ToolObject>): List<ToolCallback> =
    safelyGetTools(instances).map { it.toSpringToolCallback() }

/**
 * Extract tools from a single ToolObject and convert to Spring AI ToolCallbacks.
 * Internal use only - external code should use [safelyGetToolsFrom].
 */
internal fun safelyGetToolCallbacksFrom(toolObject: ToolObject): List<ToolCallback> =
    safelyGetToolsFrom(toolObject).map { it.toSpringToolCallback() }

fun org.springframework.ai.chat.metadata.Usage.toEmbabelUsage(): Usage {
    return Usage(
        promptTokens = this.promptTokens,
        completionTokens = this.completionTokens,
        nativeUsage = this.nativeUsage,
    )
}
