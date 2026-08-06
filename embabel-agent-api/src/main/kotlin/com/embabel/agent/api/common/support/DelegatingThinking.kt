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
package com.embabel.agent.api.common.support

import com.embabel.agent.api.common.PromptRunner
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.thinking.ThinkingTagInjector
import com.embabel.common.core.types.ZeroToOne

/**
 * Implementation of [PromptRunner.Thinking] that delegates to a [PromptExecutionDelegate].
 */
internal data class DelegatingThinking(
    private val delegate: PromptExecutionDelegate,
    private val thinkingTag: String? = null,
) : PromptRunner.Thinking {

    override fun <T> createObjectIfPossible(
        messages: List<Message>,
        outputClass: Class<T>
    ): ThinkingResponse<T?> =
        delegateForTag().createObjectIfPossibleWithThinking(messages, outputClass)

    override fun <T> createObject(
        messages: List<Message>,
        outputClass: Class<T>
    ): ThinkingResponse<T> =
        delegateForTag().createObjectWithThinking(messages, outputClass)

    override fun respond(messages: List<Message>): ThinkingResponse<AssistantMessage> =
        delegateForTag().respondWithThinking(messages)

    override fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne
    ): ThinkingResponse<Boolean> =
        delegateForTag().evaluateConditionWithThinking(condition, context, confidenceThreshold)

    private fun delegateForTag(): PromptExecutionDelegate {
        if (thinkingTag == null) return delegate
        return delegate.withPromptContributors(
            listOf(ThinkingTagInjector(thinkingTag))
        )
    }
}
