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
package com.embabel.chat.support

import com.embabel.chat.Conversation
import com.embabel.chat.ConversationFormatter
import com.embabel.chat.Message
import com.embabel.chat.MessageFormatter
import com.embabel.chat.SimpleMessageFormatter
import com.embabel.common.ai.model.TokenCountEstimator
import org.jetbrains.annotations.ApiStatus

/**
 * Conversation formatter that selects the most recent messages
 * that fit within a token budget. Accumulates messages from the end
 * backward until the budget is exhausted.
 *
 * Uses [TokenCountEstimator]<[Message]> to estimate token cost at the message level,
 * allowing estimators to account for per-message framing overhead (role markers,
 * special tokens) in addition to content length.
 *
 * @param tokenCountEstimator estimates tokens per message
 * @param tokenBudget maximum tokens to include
 * @param messageFormatter formats individual messages for output (default: [SimpleMessageFormatter])
 * @param startIndex number of messages to skip from the beginning (default: 0)
 */
@ApiStatus.Experimental
class TokenBudgetConversationFormatter @JvmOverloads constructor(
    private val tokenCountEstimator: TokenCountEstimator<Message>,
    private val tokenBudget: Int,
    private val messageFormatter: MessageFormatter = SimpleMessageFormatter,
    private val startIndex: Int = 0,
) : ConversationFormatter {

    override fun format(conversation: Conversation): String {
        var remaining = tokenBudget
        val selected = mutableListOf<Message>()
        for (message in conversation.messages.drop(startIndex).asReversed()) {
            val cost = tokenCountEstimator.estimate(message)
            if (cost > remaining) break
            selected.add(message)
            remaining -= cost
        }
        // Exploratory: the "\n" separator between formatted messages is not included
        // in the per-message token budget. For strict adherence, separator cost should
        // be accounted for — see TokenBudgetRetrievableResultsFormatter for that pattern.
        return selected.asReversed().joinToString("\n") { messageFormatter.format(it) }
    }
}
