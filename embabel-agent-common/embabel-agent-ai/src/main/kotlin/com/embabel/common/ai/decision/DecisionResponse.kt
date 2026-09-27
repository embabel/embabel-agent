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
package com.embabel.common.ai.decision

import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import org.jetbrains.annotations.ApiStatus
import java.util.Objects

/**
 * The answer to one question in a decision response. There is one implementation per question
 * kind and no other implementation can exist, so a Kotlin `when` or a Java `switch` over an
 * answer covers every case.
 *
 * An answer can be read without the question that produced it. It holds the question's name,
 * kind and definition id, the public options or levels, and the typed outcome. It never holds
 * the question's instructions or the decision input.
 *
 * ```java
 * String route = switch (response.answer("department")) {
 *     case DecisionAnswer.Choice choice
 *         when choice.getOutcome() instanceof ClassificationResult.Selected selected -> selected.getCategoryId();
 *     case DecisionAnswer.Choice choice -> "triage-queue";
 *     case DecisionAnswer.Proposition proposition -> "not a choice";
 *     case DecisionAnswer.Rating rating -> "not a choice";
 * };
 * ```
 */
@ApiStatus.Experimental
sealed interface DecisionAnswer {

    /** The name of the question this answers. It is unique within a response. */
    val name: String

    /** The kind of the question this answers. */
    val kind: QuestionKind

    /** The `d1-` definition id of the question this answers. */
    val definitionId: String

    /**
     * The answer to a proposition question.
     *
     * @property outcome what the model concluded about the proposition
     */
    @ApiStatus.Experimental
    class Proposition private constructor(
        override val name: String,
        override val definitionId: String,
        val outcome: PropositionResult,
    ) : DecisionAnswer {

        init {
            AnswerRules.requireIdentity(name, definitionId)
        }

        override val kind: QuestionKind get() = QuestionKind.PROPOSITION

        override fun equals(other: Any?): Boolean =
            this === other || other is Proposition &&
                name == other.name && definitionId == other.definitionId && outcome == other.outcome

        override fun hashCode(): Int = Objects.hash(kind, name, definitionId, outcome)

        override fun toString(): String = "DecisionAnswer.Proposition(name=$name, outcome=$outcome)"

        internal companion object {
            // Used by DecisionResponse and the JSON bindings. Hidden from Java so answers only come from a response.
            @JvmSynthetic
            internal fun create(name: String, definitionId: String, outcome: PropositionResult): Proposition =
                Proposition(name, definitionId, outcome)
        }
    }

    /**
     * The answer to a choice question. A selected category id is always one of [options].
     *
     * @property outcome the option the model picked, or why it picked none
     */
    @ApiStatus.Experimental
    class Choice private constructor(
        override val name: String,
        override val definitionId: String,
        options: List<Category>,
        val outcome: ClassificationResult,
    ) : DecisionAnswer {

        /** The question's options in declared order. The list cannot be modified. Option ids are unique. */
        val options: List<Category> = java.util.List.copyOf(options)

        init {
            AnswerRules.requireIdentity(name, definitionId)
            require(this.options.isNotEmpty()) { "Question '$name': at least one option is required" }
            AnswerRules.requireUniqueIds(name, "option", this.options.map { it.id })
            require(outcome !is ClassificationResult.Selected || this.options.any { it.id == outcome.categoryId }) {
                "Question '$name': the selected category id is not one of its options"
            }
        }

        override val kind: QuestionKind get() = QuestionKind.CHOICE

        override fun equals(other: Any?): Boolean =
            this === other || other is Choice && name == other.name && definitionId == other.definitionId &&
                options == other.options && outcome == other.outcome

        override fun hashCode(): Int = Objects.hash(kind, name, definitionId, options, outcome)

        override fun toString(): String = "DecisionAnswer.Choice(name=$name, outcome=$outcome)"

        internal companion object {
            // Used by DecisionResponse and the JSON bindings. Hidden from Java so answers only come from a response.
            @JvmSynthetic
            internal fun create(
                name: String,
                definitionId: String,
                options: List<Category>,
                outcome: ClassificationResult,
            ): Choice = Choice(name, definitionId, options, outcome)
        }
    }

    /**
     * The answer to a rating question. Any evidence in the outcome fits [levels]: a selected level
     * is one of them, a distribution covers exactly them, and a score is at most the index of the
     * last level.
     *
     * @property outcome the rating evidence the model reported, or why it reported none
     */
    @ApiStatus.Experimental
    class Rating private constructor(
        override val name: String,
        override val definitionId: String,
        levels: List<RatingLevel>,
        val outcome: RatingResult,
    ) : DecisionAnswer {

        /** The question's levels from lowest to highest. The list cannot be modified. Level ids are unique. */
        val levels: List<RatingLevel> = java.util.List.copyOf(levels)

        init {
            AnswerRules.requireIdentity(name, definitionId)
            require(this.levels.size >= 2) { "Question '$name': at least two levels are required" }
            val levelIds = this.levels.map { it.id }
            AnswerRules.requireUniqueIds(name, "level", levelIds)
            if (outcome is RatingResult.Answered) {
                // The same checks, with the same messages, as RatingQuestionSpec.validate.
                require(outcome.selectedLevelId == null || outcome.selectedLevelId in levelIds) {
                    "Question '$name': the selected level id is not one of its levels"
                }
                require(outcome.distribution.isEmpty() || outcome.distribution.map { it.levelId }.toSet() == levelIds.toSet()) {
                    "Question '$name': the distribution must cover exactly its levels"
                }
                val score = outcome.score
                require(score == null || score.value <= this.levels.size - 1) {
                    "Question '$name': the score ${score?.value} is above the last level index ${this.levels.size - 1}"
                }
            }
        }

        override val kind: QuestionKind get() = QuestionKind.RATING

        override fun equals(other: Any?): Boolean =
            this === other || other is Rating && name == other.name && definitionId == other.definitionId &&
                levels == other.levels && outcome == other.outcome

        override fun hashCode(): Int = Objects.hash(kind, name, definitionId, levels, outcome)

        override fun toString(): String = "DecisionAnswer.Rating(name=$name, outcome=$outcome)"

        internal companion object {
            // Used by DecisionResponse and the JSON bindings. Hidden from Java so answers only come from a response.
            @JvmSynthetic
            internal fun create(
                name: String,
                definitionId: String,
                levels: List<RatingLevel>,
                outcome: RatingResult,
            ): Rating = Rating(name, definitionId, levels, outcome)
        }
    }
}

/**
 * The answers a model gave to a decision spec, one per question, in spec order.
 *
 * A response can be read without the spec. Look an answer up by name with [answer], or pass a
 * question to the typed [answer] to get its outcome with the right result type. The typed lookup
 * checks the question's full definition, so a question rebuilt with the same definition works and
 * a question whose definition has changed since the response was made is rejected.
 *
 * ```java
 * PropositionResult urgency = response.answer(urgent);
 * ClassificationResult team = response.answer(department);
 * RatingResult anger = response.answer(frustration);
 * ```
 *
 * Build a response with [builder], or record a failed request with [failed]. Two responses are
 * equal when their spec ids, modes, request failures and answers are equal.
 */
@ApiStatus.Experimental
class DecisionResponse private constructor(
    /** The `s1-` definition id of the spec this response answers. */
    val definitionId: String,
    /** How the spec was run. */
    val executionMode: ExecutionMode,
    /**
     * Why the whole request failed, or null when it did not. When it is set, every answer's outcome
     * is a failure.
     */
    val requestFailure: FailureReason?,
    answers: List<DecisionAnswer>,
) {

    /** The answers in spec order, one per question. The list cannot be modified. */
    val answers: List<DecisionAnswer> = java.util.List.copyOf(answers)

    private val answersByName: Map<String, DecisionAnswer>

    init {
        require(this.answers.isNotEmpty()) { "A decision response needs at least one answer" }
        val seen = HashSet<String>()
        val repeated = this.answers.map { it.name }.filterNot(seen::add).distinct()
        require(repeated.isEmpty()) {
            "Answer names must be unique within a decision response. Repeated: ${repeated.joinToString { "'$it'" }}"
        }
        if (requestFailure != null) {
            val notFailed = this.answers.filterNot(AnswerRules::isFailure).map { it.name }
            require(notFailed.isEmpty()) {
                "A response with a request failure can only hold failure outcomes. " +
                    "Not failed: ${notFailed.joinToString { "'$it'" }}"
            }
        }
        // The spec id covers every question id in order, so this catches a dropped, extra or reordered answer.
        val actual = DefinitionIds.spec(this.answers.map { it.definitionId })
        require(actual == definitionId) {
            "The answers do not match the response's spec. Expected spec id '$definitionId', " +
                "but the answers give '$actual'."
        }
        answersByName = this.answers.associateBy { it.name }
    }

    /**
     * Returns the answer with the given name. Use this when the name is only known at run time,
     * then check the answer's class or [DecisionAnswer.kind] to read its outcome.
     *
     * @param name the question name
     * @return the answer with that name
     * @throws IllegalArgumentException if this response has no answer with that name
     */
    fun answer(name: String): DecisionAnswer = requireNotNull(answersByName[name]) {
        "The response has no answer named '$name'. Answers: ${answers.joinToString { "'${it.name}'" }}"
    }

    /**
     * Returns the outcome for the given question, typed by the question. The question does not
     * have to be the same object that built the response. It must have the same name, kind and
     * definition id as the answer, so any change to its instructions, options or levels is
     * rejected.
     *
     * Each question class fixes its result type: a `PropositionQuestionSpec` is a
     * `Question<PropositionResult>`, a `ChoiceQuestionSpec` is a `Question<ClassificationResult>`
     * and a `RatingQuestionSpec` is a `Question<RatingResult>`. The lookup only returns an outcome
     * from the answer class that matches the question's class, so the outcome always has type [R].
     *
     * @param question the question whose answer to read
     * @return the outcome of that question
     * @throws IllegalArgumentException if this response has no answer with the question's name, the
     * answer is of another kind, or the answer was given for a different definition of the question
     */
    @Suppress("UNCHECKED_CAST")
    fun <R : Any> answer(question: Question<R>): R {
        val found = answer(question.name)
        val outcome: Any? = when (question) {
            is PropositionQuestionSpec -> if (found is DecisionAnswer.Proposition) found.outcome else null
            is ChoiceQuestionSpec -> if (found is DecisionAnswer.Choice) found.outcome else null
            is RatingQuestionSpec -> if (found is DecisionAnswer.Rating) found.outcome else null
        }
        require(outcome != null) {
            "Answer '${question.name}' is a ${found.kind.wireName} answer, " +
                "but the question is a ${question.kind.wireName} question"
        }
        require(found.definitionId == question.definitionId) {
            "Answer '${question.name}' was given for a different definition of the question. " +
                "The response holds definition id '${found.definitionId}' and the question has '${question.definitionId}'."
        }
        return outcome as R
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is DecisionResponse && definitionId == other.definitionId &&
            executionMode == other.executionMode && requestFailure == other.requestFailure && answers == other.answers

    override fun hashCode(): Int = Objects.hash(definitionId, executionMode, requestFailure, answers)

    override fun toString(): String =
        "DecisionResponse(executionMode=$executionMode, requestFailure=$requestFailure, answers=$answers)"

    /**
     * Collects one answer per question of a spec. Get one from [DecisionResponse.builder].
     * Answers can be added in any order, and the built response lists them in spec order.
     * A builder is not safe for use from several threads at once.
     */
    @ApiStatus.Experimental
    class Builder private constructor(
        private val spec: DecisionSpec,
        private val executionMode: ExecutionMode,
    ) {

        private val answers = HashMap<String, DecisionAnswer>()

        /**
         * Adds the answer to one question of the spec. A rejected answer leaves the builder as it was.
         *
         * @param question a question of the spec, or a question with the same definition
         * @param outcome the outcome for that question, which must fit its options or levels
         * @return this builder
         * @throws IllegalArgumentException if the spec has no question with this name, the spec's
         * question has a different definition, the question already has an answer, the outcome is
         * of another kind, or the outcome does not fit the question
         */
        fun <R : Any> answer(question: Question<R>, outcome: R): Builder {
            val declared = spec.question(question.name)
            require(declared != null) { "The spec has no question named '${question.name}'" }
            require(declared.definitionId == question.definitionId) {
                "Question '${question.name}' has a different definition from the one in the spec"
            }
            require(question.name !in answers) { "Question '${question.name}' already has an answer" }
            answers[question.name] = when (question) {
                is PropositionQuestionSpec -> {
                    require(outcome is PropositionResult) { outcomeTypeMessage(question, "PropositionResult") }
                    DecisionAnswer.Proposition.create(question.name, question.definitionId, outcome)
                }
                is ChoiceQuestionSpec -> {
                    require(outcome is ClassificationResult) { outcomeTypeMessage(question, "ClassificationResult") }
                    DecisionAnswer.Choice.create(
                        question.name, question.definitionId, question.options, question.validate(outcome),
                    )
                }
                is RatingQuestionSpec -> {
                    require(outcome is RatingResult) { outcomeTypeMessage(question, "RatingResult") }
                    DecisionAnswer.Rating.create(
                        question.name, question.definitionId, question.levels, question.validate(outcome),
                    )
                }
            }
            return this
        }

        /**
         * Returns a new response holding the answers added so far, in spec order.
         *
         * @throws IllegalArgumentException if a question of the spec has no answer
         */
        fun build(): DecisionResponse {
            val missing = spec.questions.map { it.name }.filterNot(answers::containsKey)
            require(missing.isEmpty()) {
                "Every question in the spec needs an answer. Missing: ${missing.joinToString { "'$it'" }}"
            }
            return DecisionResponse(spec.definitionId, executionMode, null, spec.questions.map { answers.getValue(it.name) })
        }

        private fun outcomeTypeMessage(question: Question<*>, resultType: String): String =
            "Question '${question.name}' is a ${question.kind.wireName} question, so its outcome must be a $resultType"

        internal companion object {
            // Used by DecisionResponse.builder. Hidden from Java so a builder can only come from there.
            @JvmSynthetic
            internal fun create(spec: DecisionSpec, executionMode: ExecutionMode): Builder = Builder(spec, executionMode)
        }
    }

    /**
     * Creates decision responses.
     */
    companion object {

        /**
         * Starts a builder for a response to the given spec.
         *
         * @param spec the spec being answered
         * @param executionMode how the spec was run
         * @return a new builder with no answers
         */
        @JvmStatic
        fun builder(spec: DecisionSpec, executionMode: ExecutionMode): Builder = Builder.create(spec, executionMode)

        /**
         * Returns a response for a request that failed as a whole. Every question gets the failure
         * outcome of its kind with the given reason, and [requestFailure] is set to that reason.
         *
         * @param spec the spec that was being answered
         * @param executionMode how the spec was run
         * @param reason why the request failed
         * @return a response whose outcomes are all failures
         */
        @JvmStatic
        fun failed(spec: DecisionSpec, executionMode: ExecutionMode, reason: FailureReason): DecisionResponse =
            DecisionResponse(spec.definitionId, executionMode, reason, spec.questions.map { AnswerRules.failure(it, reason) })

        // Rebuilds a response without its spec, for the JSON bindings. The constructor checks every
        // invariant, including that the answers' definition ids give the stated spec id. Hidden from Java.
        @JvmSynthetic
        internal fun create(
            definitionId: String,
            executionMode: ExecutionMode,
            requestFailure: FailureReason?,
            answers: List<DecisionAnswer>,
        ): DecisionResponse = DecisionResponse(definitionId, executionMode, requestFailure, answers)
    }
}

// A private object compiles to a package-private class, so these shared checks add nothing Java can see.
private object AnswerRules {

    fun requireIdentity(name: String, definitionId: String) {
        require(name.isNotBlank()) { "Answer name must not be blank" }
        require(definitionId.isNotBlank()) { "Answer '$name': definition id must not be blank" }
    }

    fun requireUniqueIds(name: String, entry: String, ids: List<String>) {
        val seen = HashSet<String>()
        val repeated = ids.filterNot(seen::add).distinct()
        require(repeated.isEmpty()) {
            "Question '$name': $entry ids must be unique. Repeated: ${repeated.joinToString { "'$it'" }}"
        }
    }

    fun isFailure(answer: DecisionAnswer): Boolean = when (answer) {
        is DecisionAnswer.Proposition -> answer.outcome is PropositionResult.Failure
        is DecisionAnswer.Choice -> answer.outcome is ClassificationResult.Failure
        is DecisionAnswer.Rating -> answer.outcome is RatingResult.Failure
    }

    fun failure(question: Question<*>, reason: FailureReason): DecisionAnswer = when (question) {
        is PropositionQuestionSpec ->
            DecisionAnswer.Proposition.create(question.name, question.definitionId, PropositionResult.Failure(reason))
        is ChoiceQuestionSpec -> DecisionAnswer.Choice.create(
            question.name, question.definitionId, question.options, ClassificationResult.Failure(reason),
        )
        is RatingQuestionSpec -> DecisionAnswer.Rating.create(
            question.name, question.definitionId, question.levels, RatingResult.Failure(reason),
        )
    }
}
