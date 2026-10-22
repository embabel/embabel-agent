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

import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelCapability
import org.jetbrains.annotations.ApiStatus

/**
 * A model that can stream its response to a list of chat messages as it is generated.
 *
 * An [LlmService] that implements this interface has a [createMessageStreamer] method. That does
 * not mean the model streams: some providers accept a streaming request for a model and then
 * return an error. Call [LlmService.supportsStreaming] to find out whether a particular model
 * streams. The Spring AI implementation answers it by sending a test streaming request to the
 * provider.
 */
@ApiStatus.Experimental
interface StreamingChatCompletion : ModelCapability {

    /**
     * Create a message streamer for this model configured with the given options.
     *
     * The message streamer handles streaming LLM API calls. Tool execution is
     * handled internally by the underlying LLM framework during streaming.
     *
     * @param options Configuration options for the LLM call (temperature, max tokens, etc.)
     * @return A message streamer configured for this model
     */
    fun createMessageStreamer(options: LlmOptions): LlmMessageStreamer
}
