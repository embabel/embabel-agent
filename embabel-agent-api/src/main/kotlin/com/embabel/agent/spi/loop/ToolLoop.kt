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
package com.embabel.agent.spi.loop

import com.embabel.agent.api.tool.Tool
import com.embabel.chat.Message

/**
 * Embabel's own tool execution loop.
 *
 * This gives us full control over:
 * - Message capture and history management
 * - Dynamic tool injection via strategies
 * - Observability and event emission
 *
 * This interface is framework-agnostic - implementations use [LlmMessageSender] for LLM communication,
 * allowing different backends (Spring AI, LangChain4j, etc.) to be plugged in, as well as direct LLM access.
 */
interface ToolLoop {

    /**
     * Execute a conversation with tool calling until completion.
     *
     * @param initialMessages The starting messages (system + user)
     * @param initialTools The initially available tools
     * @param outputParser Function to parse the final response to the output type
     * @return The result containing parsed output and conversation history
     */
    fun <O> execute(
        initialMessages: List<Message>,
        initialTools: List<Tool>,
        outputParser: (String) -> O,
    ): ToolLoopResult<O>
}
