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
package com.embabel.agent.eval.client

interface InSession {
    val sessionId: String
}

data class ChatRequest(
    override val sessionId: String,
    val message: OpenAiCompatibleMessage,
    val model: String,
) : InSession {

    constructor(sessionId: String, message: String, model: String) : this(
        sessionId = sessionId,
        message = SimpleOpenAiCompatibleMessage(
            content = message,
            role = MessageRole.user,
        ),
        model = model,
    )
}

/**
 * @param history List of previous messages in the conversation
 */
data class MessageResponse(
    override val sessionId: String,
    val chatbot: String,
    val message: OpenAiCompatibleMessage,
    val history: List<OpenAiCompatibleMessage>,
    val events: List<GenerationEvent>,
) : InSession {

    val systemPrompt: String get() = events.filterIsInstance<SystemPromptEvent>().firstOrNull()?.systemPrompt ?: ""
}
