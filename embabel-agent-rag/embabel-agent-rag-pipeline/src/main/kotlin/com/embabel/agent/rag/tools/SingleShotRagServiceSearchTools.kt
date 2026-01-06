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
package com.embabel.agent.rag.tools

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.common.util.loggerFor

/**
 * Expose a RagService as tools.
 * Once the tools instance is created,
 * options such as similarity cutoff are immutable
 * and will be used consistently in all calls.
 * The LLM needs to provide only the search query.
 */
class SingleShotRagServiceSearchTools(
    val options: RagOptions,
) {

    @LlmTool(description = "Search for information relating to this query. Returns detailed results")
    fun search(
        @LlmTool.Param(
            description = "Standalone query to search for. Include sufficient context",
        )
        query: String,
    ): String {
        val ragResponse = options.ragService.search(options.toRequest(query))
        val asString = options.retrievableResultsFormatter.formatResults(ragResponse)
        loggerFor<SingleShotRagServiceSearchTools>().debug("RagResponse for query [{}]:\n{}", query, asString)
        return asString
    }

}
