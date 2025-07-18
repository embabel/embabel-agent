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
package com.embabel.agent.rag.tools

import com.embabel.agent.api.common.support.SelfToolCallbackPublisher
import com.embabel.agent.rag.RagRequest
import com.embabel.agent.rag.RagResponseFormatter
import com.embabel.agent.rag.RagService
import com.embabel.agent.rag.SimpleRagResponseFormatter
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * Expose a RagService as tools
 */
class RagServiceTools(
    private val ragService: RagService,
    private val toolFormatter: RagResponseFormatter = SimpleRagResponseFormatter
) : SelfToolCallbackPublisher {

    @Tool(description = "Query the RAG service")
    fun search(
        @ToolParam(description = "Query to search for")
        query: String
    ): String {
        return toolFormatter.format(ragService.search(RagRequest(query)))
    }
}
