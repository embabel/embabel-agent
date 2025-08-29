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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.event.AbstractAgentProcessEvent
import com.embabel.agent.spi.LlmInteraction
import com.embabel.common.ai.model.Llm
import org.springframework.ai.chat.prompt.Prompt

/**
 * Spring AI low level event
 */
class ChatModelCallEvent<O> internal constructor(
    agentProcess: AgentProcess,
    val outputClass: Class<O>,
    val interaction: LlmInteraction,
    val llm: Llm,
    val springAiPrompt: Prompt,
) : AbstractAgentProcessEvent(agentProcess)
