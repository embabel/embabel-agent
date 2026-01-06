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
package com.embabel.agent.eval.support

import com.embabel.agent.eval.client.FunctionCallEvent
import com.embabel.agent.eval.client.GenerationEvent
import com.embabel.agent.eval.client.MessageRole


data class EvaluationInProgress(
    val sessionId: String,
    val model: String,
    override val job: EvaluationJob,
    private val _transcript: MutableList<TimedOpenAiCompatibleMessage> = mutableListOf(),
    var done: Boolean = false,
    private var _failures: Int = 0,
) : EvaluationRun {

    fun recordFailure() {
        _failures++
    }

    val failureCount get() = _failures

    /**
     * All events we've seen
     */
    val events: List<GenerationEvent>
        get() =
            _transcript.flatMap { it.events }

    data class FunctionCall(val function: String, val args: Map<String, Any>)

    val functionCalls
        get(): List<FunctionCall> =
            events.filterIsInstance<FunctionCallEvent>()
                .map { FunctionCall(it.request.function.name, it.request.arguments) }

    override val transcript: List<TimedOpenAiCompatibleMessage>
        get() = _transcript

    internal fun addEvaluatorUserMessage(content: String, timeTakenMillis: Long) {
        _transcript.add(
            TimedOpenAiCompatibleMessage(
                content = content,
                role = MessageRole.user,
                timeTakenMillis = timeTakenMillis,
                events = emptyList(),
            )
        )
    }

    internal fun addAssistantMessage(content: String, timeTakenMillis: Long, events: List<GenerationEvent>) {
        _transcript.add(
            TimedOpenAiCompatibleMessage(
                content = content,
                role = MessageRole.assistant,
                timeTakenMillis = timeTakenMillis,
                events = events,
            )
        )
    }
}
