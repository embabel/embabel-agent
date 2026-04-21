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
package com.embabel.agent.spi.support

import com.embabel.chat.Message
import com.embabel.chat.SystemMessage
import com.embabel.common.ai.prompt.PromptContributor

/**
 * Separator used between prompt elements when building consolidated prompts.
 */
internal const val PROMPT_ELEMENT_SEPARATOR = "\n----\n"

/**
 * Builds a prompt contributions string from prompt contributors.
 *
 * @param interactionContributors Contributors from the LlmInteraction
 * @param llmContributors Contributors from the LlmService
 * @return Consolidated prompt contributions string
 */
internal fun buildPromptContributionsString(
    interactionContributors: List<PromptContributor>,
    llmContributors: List<PromptContributor>,
): String = (interactionContributors + llmContributors)
    .joinToString(PROMPT_ELEMENT_SEPARATOR) { it.contribution() }

/**
 * Partitions messages into system message content and non-system messages.
 * This enables consolidating all system content at the beginning of the prompt.
 *
 * @param messages The messages to partition
 * @return Pair of (system content strings, non-system messages)
 */
internal fun partitionMessages(messages: List<Message>): Pair<List<String>, List<Message>> {
    val systemContent = mutableListOf<String>()
    val nonSystemMessages = mutableListOf<Message>()
    for (message in messages) {
        if (message is SystemMessage) {
            systemContent.add(message.content)
        } else {
            nonSystemMessages.add(message)
        }
    }
    return systemContent to nonSystemMessages
}

/**
 * Consolidates multiple system content strings into a single string.
 * Follows OpenAI best practices and ensures compatibility with models like DeepSeek
 * that have strict message ordering requirements.
 *
 * @param contents The content strings to consolidate
 * @return Single consolidated string with contents joined by double newlines
 */
internal fun buildConsolidatedSystemMessage(vararg contents: String): String =
    contents.filter { it.isNotEmpty() }.joinToString("\n\n")

/**
 * Builds a message list with all system content consolidated into a single
 * system message at the beginning.
 *
 * Partitions input messages, extracts system content, merges with prompt contributions,
 * and returns a new list with consolidated system message followed by non-system messages.
 *
 * @param messages The input messages (may contain system messages to extract)
 * @param promptContributions The prompt contributions string to include
 * @return Message list with consolidated system message first
 */
internal fun buildConsolidatedPromptMessages(
    messages: List<Message>,
    promptContributions: String,
): List<Message> {
    val (systemContent, nonSystemMessages) = partitionMessages(messages)
    val allSystemContent = buildConsolidatedSystemMessage(promptContributions, *systemContent.toTypedArray())
    return buildList {
        if (allSystemContent.isNotEmpty()) {
            add(SystemMessage(allSystemContent))
        }
        addAll(nonSystemMessages)
    }
}
