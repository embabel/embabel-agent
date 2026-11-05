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
package com.embabel.agent.sandbox

import com.embabel.agent.api.tool.Tool
import com.embabel.common.util.EmbabelObjectMapperHolder

/** Publishes explicitly selected files from a [ScratchTool]'s persistent sandbox session. */
class ScratchPublishTool(private val scratchTool: ScratchTool) : Tool {
    private val objectMapper = EmbabelObjectMapperHolder.createDefault().get()

    override val definition = Tool.Definition(
        name = "scratch_publish",
        description = "Publish finished files from the persistent scratch sandbox. " +
            "Pass paths, a nonempty list of absolute container paths, even for a single file. " +
            "Only selected files are exported. Results identify any files that failed; retry only those paths.",
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter(name = "paths", type = Tool.ParameterType.ARRAY,
                description = "Absolute container paths of the finished files to publish",
                required = true, itemType = Tool.ParameterType.STRING),
        ),
    )

    override fun call(input: String): Tool.Result {
        return try {
            @Suppress("UNCHECKED_CAST")
            val params = objectMapper.readValue(input, Map::class.java) as Map<String, Any?>
            require(!params.containsKey("path")) { "Use 'paths', an array of paths, even for a single file" }
            val paths = params["paths"] as? List<*> ?: return Tool.Result.error("'paths' must be a list of strings")
            require(paths.isNotEmpty() && paths.all { it is String && it.isNotBlank() }) {
                "'paths' must contain one or more nonblank strings"
            }
            require(paths.distinct().size == paths.size) { "'paths' must not contain duplicates" }
            scratchTool.publishFiles(paths.filterIsInstance<String>())
        } catch (e: Exception) {
            Tool.Result.error("Invalid publication request: ${e.message}")
        }
    }
}
