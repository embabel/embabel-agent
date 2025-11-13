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

import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMessage
import org.springframework.ai.chat.messages.Message as SpringAiMessage
import org.springframework.ai.chat.messages.SystemMessage as SpringAiSystemMessage
import org.springframework.ai.chat.messages.UserMessage as SpringAiUserMessage

/**
 * Convert one of our messages to a Spring AI message.
 */
fun Message.toSpringAiMessage(): SpringAiMessage {
    val metadata: Map<String, Any> = emptyMap()
    return when (this) {
        is AssistantMessage -> SpringAiAssistantMessage(this.content)

        is UserMessage -> SpringAiUserMessage.builder().text(this.content).metadata(metadata).build()

        is SystemMessage -> SpringAiSystemMessage.builder().text(this.content).metadata(metadata).build()
    }
}
