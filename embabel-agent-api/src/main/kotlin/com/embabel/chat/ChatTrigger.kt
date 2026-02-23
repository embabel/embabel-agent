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
package com.embabel.chat

import com.embabel.agent.api.identity.User

/**
 * System-initiated event that triggers a chatbot response without entering the conversation.
 * The prompt reaches the LLM but is never stored as a visible message.
 *
 * Use cases:
 * - Welcome greetings: `ChatTrigger("Greet new user jasper", onBehalfOf = listOf(jasper))`
 * - Daily briefings: `ChatTrigger("Give morning briefing", onBehalfOf = listOf(subscriber))`
 * - Group notifications: `ChatTrigger("Surf is up at North Beach", onBehalfOf = surfers)`
 *
 * @param prompt the prompt to send to the LLM (not stored in conversation)
 * @param onBehalfOf the users this trigger is for — single for personalized, multiple for group
 */
data class ChatTrigger(
    val prompt: String,
    val onBehalfOf: List<User>,
)
