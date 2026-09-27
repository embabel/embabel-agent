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

import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Question
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory

/**
 * Turns the answers a provider returned for a whole spec into a [DecisionResponse]. A provider
 * reports each answer it read under the question name it mapped the answer to, and [build]
 * applies the answer rules.
 *
 * - After [unsafe], every question fails and the response's request failure is
 *   [FailureReason.INVALID_RESPONSE].
 * - A name received twice through any method fails the request the same way. A repeated name
 *   means the provider's answers cannot be matched to questions safely.
 * - A name the spec does not hold is ignored and counted in the WARN line. Its siblings are kept.
 * - A question with no answer, an answer of the wrong kind, an unreadable answer, or an outcome
 *   the question's `validate` rejects fails only that question with [FailureReason.INVALID_RESPONSE].
 * - The response lists the answers in spec order, whatever order they arrived in.
 *
 * Outcomes pass through unchanged, so any confidence or evidence in them is what the provider reported.
 *
 * When any anomaly occurs, [build] logs one WARN naming the service, each affected spec question
 * with its [Anomaly], and the unexpected count. Logged question names come from the spec. Received
 * keys, ids and outcomes never appear in a log line or exception message, because a provider can
 * be steered to echo its input there.
 *
 * An assembler builds one response. It is not safe for use from several threads at once.
 */
@ApiStatus.Experimental
class DecisionResponseAssembler private constructor(
    private val spec: DecisionSpec,
    private val serviceName: String,
) {

    /**
     * What was wrong with a provider's answer, as named in the anomaly log line.
     */
    @ApiStatus.Experimental
    enum class Anomaly {
        /** A spec question received no answer. */
        MISSING,

        /** The answer's kind differs from the question's kind. */
        WRONG_KIND,

        /** The provider could not read the answer. */
        UNREADABLE,

        /** A selected category or level id is not one of the question's options or levels. */
        OUT_OF_DOMAIN,

        /** A number in the answer lies outside the range the question allows. */
        OUT_OF_RANGE,

        /** A distribution does not cover exactly the question's options or levels, or does not sum to 1. */
        BAD_DISTRIBUTION,

        /** A name was received more than once. */
        DUPLICATE,

        /** An answer's key maps to no question. */
        UNEXPECTED,

        /** The response envelope is corrupt, so no answer can be matched to a question. */
        UNSAFE_ENVELOPE,
    }

    private sealed interface Received {
        data class Outcome(val kind: QuestionKind, val value: Any) : Received
        data class Unreadable(val anomaly: Anomaly) : Received
    }

    private val received = HashMap<String, Received>()
    private val duplicates = LinkedHashSet<String>()
    private var unexpected = 0
    private var unsafe = false
    private var built = false

    init {
        require(serviceName.isNotBlank()) { "Service name must not be blank" }
    }

    /**
     * Records a proposition answer.
     *
     * @param name the question name the provider's answer maps to
     * @param outcome the outcome the provider reported
     * @return this assembler
     */
    fun proposition(name: String, outcome: PropositionResult): DecisionResponseAssembler =
        record(name, Received.Outcome(QuestionKind.PROPOSITION, outcome))

    /**
     * Records a choice answer.
     *
     * @param name the question name the provider's answer maps to
     * @param outcome the outcome the provider reported
     * @return this assembler
     */
    fun choice(name: String, outcome: ClassificationResult): DecisionResponseAssembler =
        record(name, Received.Outcome(QuestionKind.CHOICE, outcome))

    /**
     * Records a rating answer.
     *
     * @param name the question name the provider's answer maps to
     * @param outcome the outcome the provider reported
     * @return this assembler
     */
    fun rating(name: String, outcome: RatingResult): DecisionResponseAssembler =
        record(name, Received.Outcome(QuestionKind.RATING, outcome))

    /**
     * Records an answer the provider could not read, as [Anomaly.UNREADABLE].
     *
     * @param name the question name the provider's answer maps to
     * @return this assembler
     */
    fun unreadable(name: String): DecisionResponseAssembler = unreadable(name, Anomaly.UNREADABLE)

    /**
     * Records an answer the provider could not use, with the reason.
     *
     * @param name the question name the provider's answer maps to
     * @param anomaly [Anomaly.UNREADABLE], [Anomaly.OUT_OF_RANGE] or [Anomaly.BAD_DISTRIBUTION]
     * @return this assembler
     * @throws IllegalArgumentException if the anomaly is another kind, which the assembler detects itself
     */
    fun unreadable(name: String, anomaly: Anomaly): DecisionResponseAssembler {
        require(anomaly in REPORTABLE) {
            "unreadable accepts ${REPORTABLE.joinToString()}, but got $anomaly. The assembler detects the other anomalies itself."
        }
        return record(name, Received.Unreadable(anomaly))
    }

    /**
     * Records an answer whose key maps to no question.
     *
     * @return this assembler
     */
    fun unexpected(): DecisionResponseAssembler = apply {
        requireOpen()
        unexpected++
    }

    /**
     * Records that the response envelope is corrupt, so no answer can be matched to a question.
     * The built response fails every question.
     *
     * @return this assembler
     */
    fun unsafe(): DecisionResponseAssembler = apply {
        requireOpen()
        unsafe = true
    }

    /**
     * Returns how many answers so far named no spec question, counting [unexpected] calls.
     */
    internal fun unexpectedCount(): Int = unexpected

    /**
     * Returns the response the recorded answers give under the answer rules.
     *
     * @return the response, in spec order
     * @throws IllegalStateException if this assembler has already built a response
     */
    fun build(): DecisionResponse {
        requireOpen()
        built = true
        if (unsafe) {
            logger.warn(
                "Decision service '{}' returned answers that cannot be matched to questions ({}); " +
                    "every question failed with {}",
                serviceName, Anomaly.UNSAFE_ENVELOPE, FailureReason.INVALID_RESPONSE,
            )
            return DecisionResponse.failed(spec, FailureReason.INVALID_RESPONSE)
        }
        if (duplicates.isNotEmpty()) {
            val named = spec.questions.filter { it.name in duplicates }.map { it.name to Anomaly.DUPLICATE }
            warn(named, requestFailed = true)
            return DecisionResponse.failed(spec, FailureReason.INVALID_RESPONSE)
        }
        val anomalies = ArrayList<Pair<String, Anomaly>>()
        val builder = DecisionResponse.builder(spec)
        for (question in spec.questions) {
            val anomaly = place(builder, question, received[question.name])
            if (anomaly != null) {
                anomalies += question.name to anomaly
                fail(builder, question)
            }
        }
        if (anomalies.isNotEmpty() || unexpected > 0) {
            warn(anomalies, requestFailed = false)
        }
        return builder.build()
    }

    private fun record(name: String, answer: Received): DecisionResponseAssembler = apply {
        requireOpen()
        if (spec.question(name) == null) {
            unexpected++
        }
        if (received.putIfAbsent(name, answer) != null) {
            duplicates += name
        }
    }

    private fun requireOpen() {
        check(!built) { "This assembler has already built its response. Use a new assembler for each response." }
    }

    // Adds the received answer for one question and returns null, or returns the anomaly that fails it.
    private fun place(builder: DecisionResponse.Builder, question: Question<*>, answer: Received?): Anomaly? {
        if (answer == null) return Anomaly.MISSING
        if (answer is Received.Unreadable) return answer.anomaly
        val outcome = answer as Received.Outcome
        if (outcome.kind != question.kind) return Anomaly.WRONG_KIND
        when (question) {
            is PropositionQuestionSpec -> builder.answer(question, outcome.value as PropositionResult)
            is ChoiceQuestionSpec -> {
                val result = outcome.value as ClassificationResult
                if (!fits { question.validate(result) }) return Anomaly.OUT_OF_DOMAIN
                builder.answer(question, result)
            }
            is RatingQuestionSpec -> {
                val result = outcome.value as RatingResult
                if (!fits { question.validate(result) }) return ratingAnomaly(question, result)
                builder.answer(question, result)
            }
        }
        return null
    }

    // The validator's message is dropped. The anomaly kind alone reaches the log.
    private inline fun fits(validate: () -> Unit): Boolean =
        try {
            validate()
            true
        } catch (rejected: IllegalArgumentException) {
            false
        }

    // Names the rule a rejected rating broke, in the order the validator checks them.
    private fun ratingAnomaly(question: RatingQuestionSpec, result: RatingResult): Anomaly {
        val answered = result as RatingResult.Answered
        val levelIds = question.levels.map { it.id }.toSet()
        return when {
            answered.selectedLevelId != null && answered.selectedLevelId !in levelIds -> Anomaly.OUT_OF_DOMAIN
            answered.distribution.isNotEmpty() && answered.distribution.map { it.levelId }.toSet() != levelIds ->
                Anomaly.BAD_DISTRIBUTION
            else -> Anomaly.OUT_OF_RANGE
        }
    }

    private fun fail(builder: DecisionResponse.Builder, question: Question<*>) {
        val reason = FailureReason.INVALID_RESPONSE
        when (question) {
            is PropositionQuestionSpec -> builder.answer(question, PropositionResult.Failure(reason))
            is ChoiceQuestionSpec -> builder.answer(question, ClassificationResult.Failure(reason))
            is RatingQuestionSpec -> builder.answer(question, RatingResult.Failure(reason))
        }
    }

    private fun warn(anomalies: List<Pair<String, Anomaly>>, requestFailed: Boolean) {
        val parts = ArrayList<String>()
        if (anomalies.isNotEmpty()) parts += anomalies.joinToString { (name, anomaly) -> "$name=$anomaly" }
        parts += "unexpected answers: $unexpected"
        if (requestFailed) parts += "every question failed with ${FailureReason.INVALID_RESPONSE}"
        logger.warn("Decision service '{}' returned answers it could not use: {}", serviceName, parts.joinToString("; "))
    }

    /**
     * Creates assemblers.
     */
    companion object {

        private val logger = LoggerFactory.getLogger(DecisionResponseAssembler::class.java)

        private val REPORTABLE = setOf(Anomaly.UNREADABLE, Anomaly.OUT_OF_RANGE, Anomaly.BAD_DISTRIBUTION)

        /**
         * Starts an assembler for one response to the given spec.
         *
         * @param spec the spec being answered
         * @param serviceName the answering service's name, used in the anomaly log line
         * @return a new assembler with no answers
         * @throws IllegalArgumentException if the service name is blank
         */
        @JvmStatic
        fun forSpec(spec: DecisionSpec, serviceName: String): DecisionResponseAssembler =
            DecisionResponseAssembler(spec, serviceName)
    }
}
