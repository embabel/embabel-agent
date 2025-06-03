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
package com.embabel.agent.spi

import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.common.ai.model.LlmOptions
import org.springframework.ai.tool.ToolCallback

/**
 * Decorate tools for use on the platform: for example, to time them and emit events.
 */
fun interface ToolDecorator {

    /**
     * Decorate the tool with some extra information.
     * @param tool The tool to decorate.
     * @param agentProcess The agent process that is using the tool.
     * @param action The action that resulted in the tool being called, if any.
     * @param llmOptions The LLM options that resulted in the tool being called.
     * @return The decorated tool.
     */
    fun decorate(
        tool: ToolCallback,
        agentProcess: AgentProcess,
        action: Action?,
        llmOptions: LlmOptions,
    ): ToolCallback
}
