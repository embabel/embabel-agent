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
import com.embabel.common.ai.decision.DecisionOption
import com.embabel.common.ai.decision.DecisionOptions
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
import java.time.Duration

/**
 * A decision service made from one provider function, its metadata and its capabilities.
 *
 * Every way of asking ends up as one [DecisionCall] handed to the executor. Before the executor
 * runs, the call is checked against the capabilities in a fixed order: question kinds, question
 * count, option and level counts, input kind, then call options. A failed check throws
 * [UnsupportedDecisionException] and the executor is never called. After it runs, a response that
 * doesn't answer the call's spec is replaced by an invalid-response failure that keeps any token
 * usage the executor reported.
 *
 * The option check compares ids against the capabilities, and then compares types too. The service
 * knows the typed keys of its own defaults and of [DecisionOptions.TIMEOUT]. A call key that shares
 * an id with one of those keys but has a different type is rejected, and so is a call that sets two
 * keys with the same id and different types. Otherwise such a value would be skipped without a word.
 *
 * A service can also carry default option values, which fill in any option the call leaves
 * unset, and a preflight check of the provider's own that runs after the capability checks. When
 * the call has a timeout, the executor gets whatever is left of it once the checks and the
 * preflight are done.
 *
 * @param preflight runs on the call the executor would get, and stops the ask by throwing
 * @param defaults option values used when the call sets no value of its own for that option
 * @param nanoTime the clock used to measure the timeout budget, which tests replace
 * @throws IllegalArgumentException if a default's option isn't listed in the capabilities, its
 * value isn't an instance of the option's type, or its key shares an id with another known key and
 * has a different type
 */
internal class ExecutorDecisionService(
    private val metadata: DecisionServiceMetadata,
    private val capabilities: DecisionCapabilities,
    private val executor: DecisionExecutor,
    private val preflight: ((DecisionCall) -> Unit)? = null,
    private val defaults: Map<DecisionOption<*>, Any> = emptyMap(),
    private val nanoTime: () -> Long = System::nanoTime,
) : DecisionService, DecisionCallExecution, QuestionSetExecution {

    init {
        val supported = capabilities.supportedCallOptionIds.orEmpty()
        val unlisted = defaults.keys.filter { it.id !in supported }
        require(unlisted.isEmpty()) {
            "Decision service '${metadata.name}' has default options its capabilities don't list: " +
                "${unlisted.joinToString { "'${it.id}'" }}. List them with acceptingOption, or drop the defaults"
        }
        defaults.forEach { (key, value) ->
            require(key.type.isInstance(value)) {
                "Default option '${key.id}' needs a value of type ${key.type.name}, but got ${value.javaClass.name}"
            }
        }
        val clashing = clashingIds(defaults.keys + DecisionOptions.TIMEOUT)
        require(clashing.isEmpty()) {
            "Decision service '${metadata.name}' has default options whose ids are used with more than one type: " +
                clashing.joinToString { "'$it'" }
        }
    }

    // The typed keys this service knows, by id: its defaults' keys and the framework timeout.
    private val knownKeys: Map<String, DecisionOption<*>> =
        (defaults.keys + DecisionOptions.TIMEOUT).associateBy { it.id }

    override val name: String get() = metadata.name

    override val provider: String get() = metadata.provider

    override fun capabilities(): DecisionCapabilities = capabilities

    override fun askCall(call: DecisionCall): DecisionResponse {
        val started = nanoTime()
        val accepted = check(withDefaults(call))
        val timeout = accepted.option(DecisionOptions.TIMEOUT)
        if (timeout != null && (timeout.isZero || timeout.isNegative)) {
            reject("its timeout must be longer than zero")
        }
        preflight?.invoke(accepted)
        val budgeted = if (timeout == null) {
            accepted
        } else {
            val left = timeout.minus(Duration.ofNanos(nanoTime() - started))
            if (left.isZero || left.isNegative) return DecisionResponse.failed(call.spec, FailureReason.UNAVAILABLE)
            accepted.withOption(DecisionOptions.TIMEOUT, left)
        }
        // The executor runs outside the try on purpose, so its own exceptions reach the caller as they are.
        val response: DecisionResponse? = executor.execute(budgeted)
        if (response == null) return DecisionResponse.failed(call.spec, FailureReason.INVALID_RESPONSE)
        try {
            response.requireMatches(call.spec)
        } catch (e: IllegalArgumentException) {
            return invalidResponse(call, response)
        }
        return response
    }

    /** Returns an invalid-response failure for the call that keeps the usage the executor reported. */
    private fun invalidResponse(call: DecisionCall, response: DecisionResponse): DecisionResponse {
        val failed = DecisionResponse.failed(call.spec, FailureReason.INVALID_RESPONSE)
        val usage = response.usage ?: return failed
        return DecisionResponse.create(failed.requestFailure, failed.answers, usage)
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

        val mistyped = call.optionKeys.filter { key -> knownKeys[key.id].let { it != null && it.type != key.type } }
            .map { it.id } + clashingIds(call.optionKeys)
        if (mistyped.isNotEmpty()) {
            reject(
                "the call sets options with a type that differs from the one this service uses for that id: " +
                    "${mistyped.distinct().joinToString { "'$it'" }}. Use the service's own option keys",
            )
        }
        return accepted
    }

    /** Returns the ids that appear on more than one of the given keys, which means with more than one type. */
    private fun clashingIds(keys: Collection<DecisionOption<*>>): List<String> =
        keys.groupBy { it.id }.filterValues { it.size > 1 }.keys.toList()

    /**
     * Adds each default whose option the call doesn't set. An option counts as set when its id
     * matches. When a call key has a default's id and a different type, the default is skipped
     * here and the option check then rejects the call.
     */
    private fun withDefaults(call: DecisionCall): DecisionCall {
        val setIds = call.optionKeys.mapTo(HashSet()) { it.id }
        return defaults.entries.fold(call) { current, (key, value) ->
            if (key.id in setIds) current else current.withDefault(key, value)
        }
    }

    private fun <T : Any> DecisionCall.withDefault(key: DecisionOption<T>, value: Any): DecisionCall =
        withOption(key, key.type.cast(value))

    private fun describe(questions: List<Question<*>>): String =
        questions.joinToString(", ") { "'${it.name}' (${it.kind})" }

    private fun reject(reason: String): Nothing =
        throw UnsupportedDecisionException("Decision service '$name' cannot answer this call because $reason.")

    private companion object {
        // The question name used when a single proposition is assessed through assess(request).
        const val PROPOSITION_NAME = "proposition"
    }
}
