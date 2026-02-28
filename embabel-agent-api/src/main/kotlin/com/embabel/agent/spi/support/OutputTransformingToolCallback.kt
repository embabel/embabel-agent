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
package com.embabel.agent.spi.support

import com.embabel.common.util.StringTransformer
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Transforms the output of a tool callback using a provided [StringTransformer].
 */
class OutputTransformingToolCallback(
    private val delegate: ToolCallback,
    private val outputTransformer: StringTransformer,
) : ToolCallback {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun call(toolInput: String): String =
        transformOutput(toolInput) { delegate.call(toolInput) }

    override fun call(toolInput: String, toolContext: ToolContext?): String =
        transformOutput(toolInput) { delegate.call(toolInput, toolContext) }

    private inline fun transformOutput(toolInput: String, action: () -> String): String {
        val rawOutput = action()
        val transformed = outputTransformer.transform(rawOutput)
        logger.debug(
            "Tool {} called with input: {}, raw output: {}, transformed output: {}",
            delegate.toolDefinition.name(),
            toolInput,
            rawOutput,
            transformed
        )
        val saving = rawOutput.length - transformed.length
        logger.debug("Saved {} bytes from {}", saving, rawOutput.length)
        return transformed
    }
}
