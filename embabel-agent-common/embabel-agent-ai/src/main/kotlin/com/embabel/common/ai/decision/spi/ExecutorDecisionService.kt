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
package com.embabel.common.ai.decision.spi

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionCall
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionInput
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.DecisionServiceMetadata
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.JsonDecisionInput
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Question
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.TextDecisionInput
import com.embabel.common.ai.decision.UnsupportedDecisionException

/**
 * A decision service made from one provider function, its metadata and its capabilities.
 *
 * Every way of asking ends up as one [DecisionCall] handed to the executor. Before the executor
 * runs, the call is checked against the capabilities in a fixed order: question kinds, question
 * count, option and level counts, input kind, then call options. A failed check throws
 * [UnsupportedDecisionException] and the executor is never called. After it runs, a response that
 * doesn't answer the call's spec is replaced by an invalid-response failure.
 */
internal class ExecutorDecisionService(
    private val metadata: DecisionServiceMetadata,
    private val capabilities: DecisionCapabilities,
    private val executor: DecisionExecutor,
) : DecisionService, DecisionCallExecution, QuestionSetExecution {

    override val name: String get() = metadata.name

    override val provider: String get() = metadata.provider

    override fun capabilities(): DecisionCapabilities = capabilities

    override fun askCall(call: DecisionCall): DecisionResponse {
        val accepted = check(call)
        // The executor runs outside the try on purpose, so its own exceptions reach the caller as they are.
        val response: DecisionResponse? = executor.execute(accepted)
        if (response == null) return DecisionResponse.failed(call.spec, FailureReason.INVALID_RESPONSE)
        try {
            response.requireMatches(call.spec)
        } catch (e: IllegalArgumentException) {
            return DecisionResponse.failed(call.spec, FailureReason.INVALID_RESPONSE)
        }
        return response
    }

    override fun askQuestionSet(request: DecisionRequest): DecisionResponse = askCall(DecisionCall.of(request))

    override fun classify(request: ClassificationRequest): ClassificationResult {
        val question = request.spec.question
        return askCall(DecisionCall.of(request)).answer(question)
    }

    override fun assess(request: PropositionRequest): PropositionResult {
        val question = Questions.named(PROPOSITION_NAME).proposition(request.proposition).build()
        val call = DecisionCall.of(TextDecisionInput(request.input), DecisionSpec.of(question))
        return askCall(call).answer(question)
    }

    override fun toString(): String = "ExecutorDecisionService(name=$name, provider=$provider)"

    /**
     * Runs every capability check on the call and returns the call the executor should see. That
     * is the same call, except that JSON is turned into text for a service that doesn't read JSON.
     */
    private fun check(call: DecisionCall): DecisionCall {
        val questions = call.spec.questions

        val unsupported = questions.filter { it.kind !in capabilities.questionKinds }
        if (unsupported.isNotEmpty()) {
            reject(
                "it does not answer ${unsupported.map { it.kind }.distinct().joinToString(" or ")} questions: " +
                    "${describe(unsupported)}. It answers ${capabilities.questionKinds.joinToString(" and ")} questions",
            )
        }

        capabilities.maxQuestions?.let { max ->
            if (questions.size > max) {
                reject(
                    "it answers at most $max questions per call and the call has ${questions.size}: " +
                        "${describe(questions)}. Split the spec into smaller calls",
                )
            }
        }

        val outOfRange = questions.mapNotNull { question ->
            val count = when (question) {
                is ChoiceQuestionSpec -> question.options.size
                is RatingQuestionSpec -> question.levels.size
                else -> return@mapNotNull null
            }
            val arity = capabilities.arity(question.kind) ?: return@mapNotNull null
            if (count in arity.min..arity.max) {
                null
            } else {
                "${describe(listOf(question))} has $count, and ${arity.min} to ${arity.max} are accepted"
            }
        }
        if (outOfRange.isNotEmpty()) {
            reject("some questions have an option or level count it does not accept: ${outOfRange.joinToString("; ")}")
        }

        val native = capabilities.nativeInputKinds.orEmpty()
        val input = call.input
        val accepted = when {
            input is TextDecisionInput -> call
            input is JsonDecisionInput && DecisionInput.JSON in native -> call
            input is JsonDecisionInput && call.nativeJsonRequired ->
                reject("it does not read JSON natively, and the call requires native JSON")
            input is JsonDecisionInput -> call.withInput(TextDecisionInput(input.toText()))
            input.kind in native -> call
            else -> reject("it does not accept input of kind '${input.kind}'")
        }

        val supportedOptions = capabilities.supportedCallOptionIds.orEmpty()
        val unsupportedOptions = call.optionKeys.filter { it.id !in supportedOptions }
        if (unsupportedOptions.isNotEmpty()) {
            reject(
                "it does not support the call options ${unsupportedOptions.joinToString { "'${it.id}'" }}. " +
                    "Ask without them, or use a service that lists them in its capabilities",
            )
        }
        return accepted
    }

    private fun describe(questions: List<Question<*>>): String =
        questions.joinToString(", ") { "'${it.name}' (${it.kind})" }

    private fun reject(reason: String): Nothing =
        throw UnsupportedDecisionException("Decision service '$name' cannot answer this call because $reason.")

    private companion object {
        // The question name used when a single proposition is assessed through assess(request).
        const val PROPOSITION_NAME = "proposition"
    }
}
