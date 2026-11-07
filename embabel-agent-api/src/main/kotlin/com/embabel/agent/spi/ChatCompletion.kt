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
package com.embabel.agent.spi

import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelCapability
import org.jetbrains.annotations.ApiStatus

/**
 * A model that can answer a list of chat messages with one response.
 *
 * Every [LlmService] has this capability.
 */
@ApiStatus.Experimental
interface ChatCompletion : ModelCapability {

    /**
     * Create a message sender for this model configured with the given options.
     *
     * The message sender handles the actual LLM API calls but does NOT execute tools -
     * it returns the LLM's response including any tool call requests.
     *
     * @param options Configuration options for the LLM call (temperature, max tokens, etc.)
     * @return A message sender configured for this model
     */
    fun createMessageSender(options: LlmOptions): LlmMessageSender
}
