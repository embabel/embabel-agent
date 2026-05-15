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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.support.ExternalToolExtractor
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback

/**
 * Discovered reflectively by core toolUtils when Spring AI is on the classpath.
 * Must be referenced only via its FQCN from core to preserve the classpath check.
 */
internal object SpringAiToolExtractor : ExternalToolExtractor {

    override fun extract(obj: Any): List<Tool> {
        if (obj is ToolCallback) {
            return listOf(obj.toEmbabelTool())
        }
        return try {
            ToolCallbacks.from(obj).map { it.toEmbabelTool() }
        } catch (_: IllegalStateException) {
            emptyList()
        }
    }
}
