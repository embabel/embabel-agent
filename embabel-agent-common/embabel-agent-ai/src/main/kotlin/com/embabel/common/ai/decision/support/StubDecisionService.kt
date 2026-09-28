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
package com.embabel.common.ai.decision.support

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.spi.NativeQuestionSetExecution
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.spi.RatingAssessment
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import java.util.EnumSet

/**
 * A decision service for tests that returns scripted outcomes and records each call.
 *
 * Outcomes for `ask`, `rate` and question `assess` calls are scripted by question name, and
 * outcomes for `assess` calls with a [PropositionRequest] by proposition text. `classify` returns
 * the choice scripted for the request's question name, and otherwise the classification scripted
 * for its set of category ids. A call with no scripted outcome throws [IllegalStateException].
 *
 * By default the stub answers a whole request in one native call. Native answers pass through the
 * same question validation as provider answers, so a scripted choice outside its question's
 * options, or rating evidence outside its levels, throws [IllegalStateException] from `ask`. A stub
 * built after [Builder.perQuestion] answers each question on its own: a choice question through
 * `classify`, and the other kinds through their per-question hooks.
 * The stub accepts every question kind unless other capabilities are set.
 *
 * ```kotlin
 * val stub = StubDecisionService.builder("triage-stub")
 *     .proposition("is_urgent", PropositionResult.Answered(true, provenance))
 *     .build()
 * ```
 */
@ApiStatus.Experimental
sealed class StubDecisionService private constructor(
    override val name: String,
    private val capabilities: DecisionCapabilities,
    private val propositions: Map<String, PropositionResult>,
    private val choices: Map<String, ClassificationResult>,
    private val ratings: Map<String, RatingResult>,
    private val assessments: Map<String, PropositionResult>,
    private val classifications: Map<Set<String>, ClassificationResult>,
) : DecisionService, PropositionAssessment, RatingAssessment {

    private val callLog = mutableListOf<String>()

    override val provider: String get() = PROVIDER

    override fun capabilities(): DecisionCapabilities = capabilities

    /**
     * Returns the names of the operations called so far, in call order: `askNative`, `assess`,
     * `classify` and `rate`. A question `assess` call is recorded as `assess`.
     *
     * @return an unmodifiable copy of the call log
     */
    fun calls(): List<String> = synchronized(callLog) { java.util.List.copyOf(callLog) }

    override fun classify(request: ClassificationRequest): ClassificationResult {
        record("classify")
        val questionName = request.spec.question.name
        choices[questionName]?.let { return it }
        val ids = request.categories.map { it.id }.toSet()
        return classifications[ids] ?: throw IllegalStateException(
            "Stub decision service '$name' has no scripted choice for question '$questionName' and no scripted " +
                "classification for category ids $ids. Script one with choice(\"$questionName\", result) or " +
                "classifying(categoryIds, result).",
        )
    }

    override fun assess(request: PropositionRequest): PropositionResult {
        record("assess")
        return assessments[request.proposition] ?: throw IllegalStateException(
            "Stub decision service '$name' has no scripted assessment for this proposition. " +
                "Script one with assessing(proposition, result).",
        )
    }

    override fun assess(input: String, question: PropositionQuestionSpec): PropositionResult {
        record("assess")
        return propositions[question.name] ?: throw unscripted(question.name, question.kind, "proposition")
    }

    override fun rate(input: String, question: RatingQuestionSpec): RatingResult {
        record("rate")
        return ratings[question.name] ?: throw unscripted(question.name, QuestionKind.RATING, "rating")
    }

    // Answers every question of the request from the scripts, for the native variant.
    protected fun answerAll(request: DecisionRequest): DecisionResponse {
        record("askNative")
        val builder = DecisionResponse.builder(request.spec)
        for (question in request.spec.questions) {
            try {
                when (question) {
                    is PropositionQuestionSpec -> builder.answer(
                        question,
                        propositions[question.name] ?: throw unscripted(question.name, question.kind, "proposition"),
                    )
                    is ChoiceQuestionSpec -> builder.answer(
                        question,
                        question.validate(choices[question.name] ?: throw unscripted(question.name, question.kind, "choice")),
                    )
                    is RatingQuestionSpec -> builder.answer(
                        question,
                        question.validate(ratings[question.name] ?: throw unscripted(question.name, question.kind, "rating")),
                    )
                }
            } catch (invalid: IllegalArgumentException) {
                throw IllegalStateException(
                    "Stub decision service '$name' has a scripted outcome for question '${question.name}' that does " +
                        "not fit the question: ${invalid.message}. Fix the scripted outcome.",
                    invalid,
                )
            }
        }
        return builder.build()
    }

    override fun toString(): String = "StubDecisionService(name=$name)"

    // The variant that answers a whole request in one native call.
    private class Native(
        name: String,
        capabilities: DecisionCapabilities,
        propositions: Map<String, PropositionResult>,
        choices: Map<String, ClassificationResult>,
        ratings: Map<String, RatingResult>,
        assessments: Map<String, PropositionResult>,
        classifications: Map<Set<String>, ClassificationResult>,
    ) : StubDecisionService(name, capabilities, propositions, choices, ratings, assessments, classifications),
        NativeQuestionSetExecution {
        override fun askNative(request: DecisionRequest): DecisionResponse = answerAll(request)
    }

    // The variant that answers each question on its own, a choice through classify.
    private class PerQuestion(
        name: String,
        capabilities: DecisionCapabilities,
        propositions: Map<String, PropositionResult>,
        choices: Map<String, ClassificationResult>,
        ratings: Map<String, RatingResult>,
        assessments: Map<String, PropositionResult>,
        classifications: Map<Set<String>, ClassificationResult>,
    ) : StubDecisionService(name, capabilities, propositions, choices, ratings, assessments, classifications)

    /**
     * Adds the operation to the call log and logs it at DEBUG.
     *
     * @param operation the operation name to record
     */
    private fun record(operation: String) {
        synchronized(callLog) { callLog += operation }
        logger.debug("Stub decision service '{}' called: {}", name, operation)
    }

    /**
     * Builds the exception thrown when a question has no scripted outcome.
     *
     * @param questionName the question name
     * @param kind the question kind
     * @param method the builder method to script an outcome with
     * @return the exception to throw
     */
    private fun unscripted(questionName: String, kind: QuestionKind, method: String) = IllegalStateException(
        "Stub decision service '$name' has no scripted outcome for $kind question '$questionName'. " +
            "Script one with $method(\"$questionName\", result).",
    )

    /**
     * Collects scripted outcomes for a [StubDecisionService]. Get one from
     * [StubDecisionService.builder]. A later outcome for the same key replaces the earlier one.
     */
    @ApiStatus.Experimental
    class Builder internal constructor(private val name: String) {

        private var capabilities = DEFAULT_CAPABILITIES
        private var native = true
        private val propositions = LinkedHashMap<String, PropositionResult>()
        private val choices = LinkedHashMap<String, ClassificationResult>()
        private val ratings = LinkedHashMap<String, RatingResult>()
        private val assessments = LinkedHashMap<String, PropositionResult>()
        private val classifications = LinkedHashMap<Set<String>, ClassificationResult>()

        /**
         * Scripts the outcome of a proposition question in `ask` and in question `assess` calls.
         *
         * @param questionName the question name
         * @param outcome the outcome to return
         * @return this builder
         */
        fun proposition(questionName: String, outcome: PropositionResult): Builder =
            apply { propositions[questionName] = outcome }

        /**
         * Scripts the outcome of a choice question in `ask`, and of `classify` for a request whose
         * question has this name.
         *
         * @param questionName the question name
         * @param outcome the outcome to return
         * @return this builder
         */
        fun choice(questionName: String, outcome: ClassificationResult): Builder =
            apply { choices[questionName] = outcome }

        /**
         * Scripts the outcome of a rating question in `ask` and `rate`.
         *
         * @param questionName the question name
         * @param outcome the outcome to return
         * @return this builder
         */
        fun rating(questionName: String, outcome: RatingResult): Builder = apply { ratings[questionName] = outcome }

        /**
         * Scripts the outcome of `assess` with a [PropositionRequest] for a proposition text.
         *
         * @param proposition the proposition text
         * @param outcome the outcome to return
         * @return this builder
         */
        fun assessing(proposition: String, outcome: PropositionResult): Builder =
            apply { assessments[proposition] = outcome }

        /**
         * Scripts the outcome of `classify` for a set of category ids, in any order.
         *
         * @param categoryIds the category ids of the request
         * @param outcome the outcome to return
         * @return this builder
         */
        fun classifying(categoryIds: Set<String>, outcome: ClassificationResult): Builder =
            apply { classifications[java.util.Set.copyOf(categoryIds)] = outcome }

        /**
         * Makes the stub answer each question on its own: a choice through `classify`, the other
         * kinds through their per-question hooks. By default the stub answers a whole request in one
         * native call.
         *
         * @return this builder
         */
        fun perQuestion(): Builder = apply { native = false }

        /**
         * Sets the capabilities the stub reports. The default is every question kind.
         *
         * @param capabilities the capabilities to report
         * @return this builder
         */
        fun capabilities(capabilities: DecisionCapabilities): Builder = apply { this.capabilities = capabilities }

        /**
         * Returns a stub holding the outcomes scripted so far.
         *
         * @return the stub
         */
        fun build(): StubDecisionService {
            val propositions = java.util.Map.copyOf(propositions)
            val choices = java.util.Map.copyOf(choices)
            val ratings = java.util.Map.copyOf(ratings)
            val assessments = java.util.Map.copyOf(assessments)
            val classifications = java.util.Map.copyOf(classifications)
            return if (native) {
                Native(name, capabilities, propositions, choices, ratings, assessments, classifications)
            } else {
                PerQuestion(name, capabilities, propositions, choices, ratings, assessments, classifications)
            }
        }
    }

    /**
     * Creates stub decision services.
     */
    companion object {
        private const val PROVIDER = "stub"

        private val DEFAULT_CAPABILITIES: DecisionCapabilities =
            DecisionCapabilities.of(EnumSet.allOf(QuestionKind::class.java))

        private val logger = LoggerFactory.getLogger(StubDecisionService::class.java)

        /**
         * Starts a builder for a stub with the given name.
         *
         * @param name the service name
         * @return a new builder with no scripted outcomes
         */
        @JvmStatic
        fun builder(name: String): Builder = Builder(name)
    }
}
