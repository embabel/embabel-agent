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

import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.common.ai.autoconfig.NativeSupport
import com.embabel.common.ai.model.LlmMetadata
import org.springframework.ai.chat.prompt.ChatOptions

/**
 * Spring AI-specific hook for translating Embabel structured-output intent into
 * Spring AI provider options.
 *
 * Core code should not know provider payload field names such as response_format.
 * Provider autoconfiguration modules can supply an implementation that copies and
 * mutates their own ChatOptions subtype when the selected model supports it.
 * This hook still routes through Spring AI option objects; it does not do direct
 * provider tag/payload injection itself.
 *
 * @param nativeSupport provider/model metadata that describes which native
 *        structured-output strategy this model supports, if any.
 */
fun interface SpringAiNativeStructuredOutputConfigurer {

    fun configure(
        options: ChatOptions,
        structuredOutput: StructuredOutputRequest?,
        nativeSupport: NativeSupport?,
        llm: LlmMetadata?,
    ): ChatOptions

    companion object {
        val NOOP = SpringAiNativeStructuredOutputConfigurer { options, _, _, _ -> options }
    }
}
