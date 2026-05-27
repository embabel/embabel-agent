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
package com.embabel.agent.spi.loop

import com.embabel.chat.EmptyLlmResponseException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Determines how the tool loop responds when the LLM returns a blank
 * text response with no tool calls. This is a known failure mode of weak
 * open-weights models (gpt-oss-20b, qwen, etc.) after a tool call when
 * they don't know how to proceed — see [EmptyLlmResponseException].
 *
 * Without a policy, the tool loop exits with empty content and the
 * caller surfaces a generic "no response" message. With a policy the
 * loop can either feed the model a synthetic nudge so it gets one more
 * chance, throw immediately, or preserve the current exit-and-let-caller
 * handle behaviour.
 *
 * @see RetryWithFeedbackPolicy
 * @see ExitOnEmptyPolicy
 */
interface EmptyResponsePolicy {

    /**
     * Handle a blank-text-no-tool-calls response from the LLM.
     *
     * @return the action the tool loop should take
     */
    fun handle(): EmptyResponseAction

    /**
     * Called whenever the LLM returns a non-blank response, allowing
     * stateful policies to reset internal counters.
     */
    fun onNonEmpty() {
        // No-op default for stateless policies
    }
}

/**
 * Action returned by [EmptyResponsePolicy.handle].
 */
sealed class EmptyResponseAction {

    /**
     * Exit the loop with the empty response (current behaviour). The
     * caller — typically the rendering layer — will throw
     * [EmptyLlmResponseException] when it tries to wrap blank text in
     * an [com.embabel.chat.AssistantMessage].
     */
    data object Exit : EmptyResponseAction()

    /**
     * Append [message] to the conversation as a [com.embabel.chat.UserMessage]
     * and re-prompt the LLM in the same loop. Lets weak models get one
     * more chance to produce an answer, bounded by the policy's own
     * retry counter.
     */
    data class FeedbackToModel(val message: String) : EmptyResponseAction()

    /**
     * Throw [EmptyLlmResponseException] immediately from inside the
     * loop. Use when retry is not desired and a typed exception is
     * preferred over the silent-exit default.
     */
    data object Throw : EmptyResponseAction()
}

/**
 * Re-prompt the LLM with [message] up to [maxRetries] times before
 * giving up. After exhaustion returns [EmptyResponseAction.Throw] so
 * the caller sees the typed exception rather than blank content.
 *
 * Counter is shared across calls and reset by [onNonEmpty]. Safe for
 * concurrent use as a Spring singleton.
 */
class RetryWithFeedbackPolicy(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val message: String = DEFAULT_MESSAGE,
) : EmptyResponsePolicy {

    private val consecutiveEmpties = AtomicInteger(0)

    override fun handle(): EmptyResponseAction {
        val attempts = consecutiveEmpties.incrementAndGet()
        return if (attempts > maxRetries) {
            EmptyResponseAction.Throw
        } else {
            EmptyResponseAction.FeedbackToModel(message)
        }
    }

    override fun onNonEmpty() {
        consecutiveEmpties.set(0)
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 1
        const val DEFAULT_MESSAGE: String =
            "Your previous turn produced no response. " +
                "Take ONE concrete action that progresses toward answering the user's most recent question — " +
                "either call a tool to gather what you still need, or write the answer using the information you already have. " +
                "Do not stay silent."
    }
}

/**
 * Preserve the current behaviour: exit the loop with empty content and
 * let the rendering layer surface the typed [EmptyLlmResponseException].
 * This is the default to keep the upgrade backwards-compatible.
 */
object ExitOnEmptyPolicy : EmptyResponsePolicy {

    override fun handle(): EmptyResponseAction = EmptyResponseAction.Exit
}
