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
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.jetbrains.annotations.ApiStatus
import java.util.Objects

/**
 * The answer to one question in a decision response. There is one implementation per question
 * kind and no other implementation can exist, so a Kotlin `when` or a Java `switch` over an
 * answer covers every case.
 *
 * An answer can be read without the question that produced it. It holds the question's name and
 * kind, the public options or levels, and the typed outcome. It never holds the question's
 * instructions or the decision input. An answer read from JSON holds its options or levels as
 * written. The typed lookup on a response compares them with a question.
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
 *
 * In JSON an answer is an object whose `kind` member names its class. It has the same form inside
 * a response and on its own.
 */
@ApiStatus.Experimental
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(DecisionAnswer.Proposition::class, name = "proposition"),
    JsonSubTypes.Type(DecisionAnswer.Choice::class, name = "choice"),
    JsonSubTypes.Type(DecisionAnswer.Rating::class, name = "rating"),
)
@JsonPropertyOrder("name", "kind", "options", "levels", "outcome")
sealed interface DecisionAnswer {

    /** The name of the question this answers. It is unique within a response. */
    @get:JsonProperty("name")
    val name: String

    /** The kind of the question this answers. */
    @get:JsonProperty("kind")
    val kind: QuestionKind

    /**
     * The answer to a proposition question.
     *
     * @property outcome what the model concluded about the proposition
     */
    @ApiStatus.Experimental
    class Proposition private constructor(
        override val name: String,
        @get:JsonProperty("outcome") val outcome: PropositionResult,
    ) : DecisionAnswer {

        init {
            AnswerRules.requireName(name)
        }

        override val kind: QuestionKind get() = QuestionKind.PROPOSITION

        override fun equals(other: Any?): Boolean =
            this === other || other is Proposition &&
                name == other.name && outcome == other.outcome

        override fun hashCode(): Int = Objects.hash(kind, name, outcome)

        override fun toString(): String = "DecisionAnswer.Proposition(name=$name, outcome=$outcome)"

        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionAnswer", name, value)

        internal companion object {
            // Used by DecisionResponse. Hidden from Java so answers only come from a response.
            @JvmSynthetic
            internal fun create(name: String, outcome: PropositionResult): Proposition = Proposition(name, outcome)

            /**
             * Builds a proposition answer from deserialized JSON fields.
             *
             * @param name the question name
             * @param outcome the outcome as read from JSON
             * @return the answer
             */
            @JvmStatic
            @JsonCreator
            private fun fromJson(
                @JsonProperty("name", required = true) name: String,
                @JsonProperty("outcome", required = true) outcome: PropositionResult,
            ): Proposition = Proposition(name, outcome)
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
        options: List<Category>,
        @get:JsonProperty("outcome") val outcome: ClassificationResult,
    ) : DecisionAnswer {

        /** The question's options in declared order. The list cannot be modified. Option ids are unique. */
        @get:JsonProperty("options")
        val options: List<Category> = java.util.List.copyOf(options)

        init {
            AnswerRules.requireName(name)
            OutcomeRules.requireOptionIds(name, this.options.map { it.id })
            OutcomeRules.fitChoice(name, this.options, outcome)
        }

        override val kind: QuestionKind get() = QuestionKind.CHOICE

        override fun equals(other: Any?): Boolean =
            this === other || other is Choice && name == other.name && options == other.options && outcome == other.outcome

        override fun hashCode(): Int = Objects.hash(kind, name, options, outcome)

        override fun toString(): String = "DecisionAnswer.Choice(name=$name, outcome=$outcome)"

        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionAnswer", name, value)

        internal companion object {
            // Used by DecisionResponse. Hidden from Java so answers only come from a response.
            @JvmSynthetic
            internal fun create(
                name: String,
                options: List<Category>,
                outcome: ClassificationResult,
            ): Choice = Choice(name, options, outcome)

            /**
             * Builds a choice answer from deserialized JSON fields.
             *
             * @param name the question name
             * @param options the options as read from JSON
             * @param outcome the outcome as read from JSON
             * @return the answer
             */
            @JvmStatic
            @JsonCreator
            private fun fromJson(
                @JsonProperty("name", required = true) name: String,
                @JsonProperty("options", required = true) options: List<Category>,
                @JsonProperty("outcome", required = true) outcome: ClassificationResult,
            ): Choice = Choice(name, options, outcome)
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
        levels: List<RatingLevel>,
        @get:JsonProperty("outcome") val outcome: RatingResult,
    ) : DecisionAnswer {

        /** The question's levels from lowest to highest. The list cannot be modified. Level ids are unique. */
        @get:JsonProperty("levels")
        val levels: List<RatingLevel> = java.util.List.copyOf(levels)

        init {
            AnswerRules.requireName(name)
            OutcomeRules.requireLevelIds(name, this.levels.map { it.id })
            OutcomeRules.fitRating(name, this.levels, outcome)
        }

        override val kind: QuestionKind get() = QuestionKind.RATING

        override fun equals(other: Any?): Boolean =
            this === other || other is Rating && name == other.name && levels == other.levels && outcome == other.outcome

        override fun hashCode(): Int = Objects.hash(kind, name, levels, outcome)

        override fun toString(): String = "DecisionAnswer.Rating(name=$name, outcome=$outcome)"

        /**
         * Rejects a JSON member this type doesn't define.
         *
         * @param name the unknown member's name
         * @param value the unknown member's value
         */
        @JsonAnySetter
        private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionAnswer", name, value)

        internal companion object {
            // Used by DecisionResponse. Hidden from Java so answers only come from a response.
            @JvmSynthetic
            internal fun create(
                name: String,
                levels: List<RatingLevel>,
                outcome: RatingResult,
            ): Rating = Rating(name, levels, outcome)

            /**
             * Builds a rating answer from deserialized JSON fields.
             *
             * @param name the question name
             * @param levels the levels as read from JSON
             * @param outcome the outcome as read from JSON
             * @return the answer
             */
            @JvmStatic
            @JsonCreator
            private fun fromJson(
                @JsonProperty("name", required = true) name: String,
                @JsonProperty("levels", required = true) levels: List<RatingLevel>,
                @JsonProperty("outcome", required = true) outcome: RatingResult,
            ): Rating = Rating(name, levels, outcome)
        }
    }
}

/**
 * The answers a model gave to a decision spec, one per question, in spec order.
 *
 * A response can be read without the spec. Look an answer up by name with [answer], or pass a
 * question to the typed [answer] to get its outcome with the right result type. The typed lookup
 * checks that the answer has the question's name and kind, and that its options or levels equal
 * the question's. A question rebuilt with the same definition works. To check a whole response
 * against a spec, for example one read back from storage, call [requireMatches].
 *
 * ```java
 * PropositionResult urgency = response.answer(urgent);
 * ClassificationResult team = response.answer(department);
 * RatingResult anger = response.answer(frustration);
 * ```
 *
 * Build a response with [builder], or record a failed request with [failed]. Two responses are
 * equal when their request failures and answers are equal.
 *
 * Reading a response from JSON runs the same checks as building one, answer by answer. It does not
 * compare the response with any spec.
 */
@ApiStatus.Experimental
@JsonPropertyOrder("requestFailure", "answers")
class DecisionResponse private constructor(
    /**
     * Why the whole request failed, or null when it did not. When it is set, every answer's outcome
     * is a failure with this same reason.
     */
    @get:JsonProperty("requestFailure")
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val requestFailure: FailureReason?,
    answers: List<DecisionAnswer>,
) {

    /** The answers in spec order, one per question. The list cannot be modified. */
    @get:JsonProperty("answers")
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
            val mismatched = this.answers.filter { AnswerRules.failureReason(it) != requestFailure }
            require(mismatched.isEmpty()) {
                "A response with request failure $requestFailure can only hold failure outcomes with that reason. " +
                    "Mismatched: " + mismatched.joinToString { "'${it.name}' (${AnswerRules.failureReason(it) ?: "not a failure"})" }
            }
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
     * have to be the same object that built the response. The answer must have the question's
     * name and kind, and a choice answer's options or a rating answer's levels must equal the
     * question's, ids and descriptions both.
     *
     * An answer holds no instructions, so a question whose instructions were reworded still
     * matches. For a proposition that means only the name and kind are checked.
     *
     * Each question class fixes its result type: a `PropositionQuestionSpec` is a
     * `Question<PropositionResult>`, a `ChoiceQuestionSpec` is a `Question<ClassificationResult>`
     * and a `RatingQuestionSpec` is a `Question<RatingResult>`. The lookup only returns an outcome
     * from the answer class that matches the question's class, so the outcome always has type [R].
     *
     * @param question the question whose answer to read
     * @return the outcome of that question
     * @throws IllegalArgumentException if this response has no answer with the question's name, the
     * answer is of another kind, or the answer's options or levels differ from the question's
     */
    @Suppress("UNCHECKED_CAST")
    fun <R : Any> answer(question: Question<R>): R {
        val found = answer(question.name)
        val mismatch = AnswerRules.mismatch(found, question)
        require(mismatch == null) { "Answer '${question.name}' does not fit the question: $mismatch" }
        // mismatch is null only when the answer's kind equals the question's, so these casts are safe.
        val outcome: Any = when (question) {
            is PropositionQuestionSpec -> (found as DecisionAnswer.Proposition).outcome
            is ChoiceQuestionSpec -> (found as DecisionAnswer.Choice).outcome
            is RatingQuestionSpec -> (found as DecisionAnswer.Rating).outcome
        }
        return outcome as R
    }

    /**
     * Checks that this response answers the given spec. There must be one answer per question, in
     * the spec's order, and each answer must fit its question the way the typed [answer] lookup
     * requires: same name and kind, and equal options or levels.
     *
     * Use it on a response that did not come from this spec's builder, such as one read from JSON.
     * As with the typed lookup, a change to a question's instructions is not detected.
     *
     * @param spec the spec this response should answer
     * @throws IllegalArgumentException if an answer is missing, extra or out of order, or an answer
     * does not fit its question
     */
    @ApiStatus.Experimental
    fun requireMatches(spec: DecisionSpec) {
        val names = answers.map { it.name }
        val expected = spec.questions.map { it.name }
        if (names != expected) {
            val missing = expected - names.toSet()
            val extra = names - expected.toSet()
            val details = buildList {
                if (missing.isNotEmpty()) add("Missing: ${missing.joinToString { "'$it'" }}.")
                if (extra.isNotEmpty()) add("Extra: ${extra.joinToString { "'$it'" }}.")
                if (isEmpty()) {
                    add("The answers are in a different order. Answers: ${names.joinToString { "'$it'" }}. " +
                        "Questions: ${expected.joinToString { "'$it'" }}.")
                }
            }
            throw IllegalArgumentException("The response does not match the spec. ${details.joinToString(" ")}")
        }
        val mismatches = spec.questions.zip(answers).mapNotNull { (question, answer) ->
            AnswerRules.mismatch(answer, question)?.let { "Answer '${question.name}': $it." }
        }
        require(mismatches.isEmpty()) { "The response does not match the spec. ${mismatches.joinToString(" ")}" }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is DecisionResponse && requestFailure == other.requestFailure && answers == other.answers

    override fun hashCode(): Int = Objects.hash(requestFailure, answers)

    override fun toString(): String = "DecisionResponse(requestFailure=$requestFailure, answers=$answers)"

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionResponse", name, value)

    /**
     * Collects one answer per question of a spec. Get one from [DecisionResponse.builder].
     * Answers can be added in any order, and the built response lists them in spec order.
     * A builder is not safe for use from several threads at once.
     */
    @ApiStatus.Experimental
    class Builder private constructor(private val spec: DecisionSpec) {

        private val answers = HashMap<String, DecisionAnswer>()

        /**
         * Adds the answer to one proposition question of the spec. A rejected answer leaves the
         * builder as it was.
         *
         * @param question a question of the spec, or an equal question
         * @param outcome what the model concluded about the proposition
         * @return this builder
         * @throws IllegalArgumentException if the spec has no question with this name, the spec's
         * question is not equal to this one, or the question already has an answer
         */
        fun answer(question: PropositionQuestionSpec, outcome: PropositionResult): Builder = add(question) {
            DecisionAnswer.Proposition.create(question.name, outcome)
        }

        /**
         * Adds the answer to one choice question of the spec. A rejected answer leaves the builder
         * as it was.
         *
         * @param question a question of the spec, or an equal question
         * @param outcome the option the model picked, or why it picked none
         * @return this builder
         * @throws IllegalArgumentException if the spec has no question with this name, the spec's
         * question is not equal to this one, the question already has an answer, or the selection
         * is not one of the question's options
         */
        fun answer(question: ChoiceQuestionSpec, outcome: ClassificationResult): Builder = add(question) {
            DecisionAnswer.Choice.create(question.name, question.options, outcome)
        }

        /**
         * Adds the answer to one rating question of the spec. A rejected answer leaves the builder
         * as it was.
         *
         * @param question a question of the spec, or an equal question
         * @param outcome the rating evidence the model reported, or why it reported none
         * @return this builder
         * @throws IllegalArgumentException if the spec has no question with this name, the spec's
         * question is not equal to this one, the question already has an answer, or the evidence
         * does not fit the question's levels
         */
        fun answer(question: RatingQuestionSpec, outcome: RatingResult): Builder = add(question) {
            DecisionAnswer.Rating.create(question.name, question.levels, outcome)
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
            return DecisionResponse(null, spec.questions.map { answers.getValue(it.name) })
        }

        /**
         * Runs the checks every answer method shares, then stores the answer that make builds.
         *
         * @param question the question being answered
         * @param make builds the answer once the checks pass
         * @return this builder
         */
        private fun add(question: Question<*>, make: () -> DecisionAnswer): Builder {
            val declared = spec.question(question.name)
            require(declared != null) { "The spec has no question named '${question.name}'" }
            require(declared == question) {
                "Question '${question.name}' has a different definition from the one in the spec"
            }
            require(question.name !in answers) { "Question '${question.name}' already has an answer" }
            answers[question.name] = make()
            return this
        }

        internal companion object {
            // Used by DecisionResponse.builder. Hidden from Java so a builder can only come from there.
            @JvmSynthetic
            internal fun create(spec: DecisionSpec): Builder = Builder(spec)
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
         * @return a new builder with no answers
         */
        @JvmStatic
        fun builder(spec: DecisionSpec): Builder = Builder.create(spec)

        /**
         * Returns a response for a request that failed as a whole. Every question gets the failure
         * outcome of its kind with the given reason, and [requestFailure] is set to that reason.
         *
         * @param spec the spec that was being answered
         * @param reason why the request failed
         * @return a response whose outcomes are all failures
         */
        @JvmStatic
        fun failed(spec: DecisionSpec, reason: FailureReason): DecisionResponse =
            DecisionResponse(reason, spec.questions.map { AnswerRules.failure(it, reason) })

        // Rebuilds a response without its spec. The constructor checks every invariant. Hidden from Java.
        @JvmSynthetic
        internal fun create(requestFailure: FailureReason?, answers: List<DecisionAnswer>): DecisionResponse =
            DecisionResponse(requestFailure, answers)

        /**
         * Reads a response from JSON through the same constructor. An explicit null request failure
         * reads the same as an absent one.
         *
         * @param requestFailure the request failure as read from JSON, or null when there is none
         * @param answers the answers as read from JSON
         * @return the response
         */
        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("requestFailure") requestFailure: FailureReason?,
            @JsonProperty("answers", required = true)
            @JsonFormat(without = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
            answers: List<DecisionAnswer>,
        ): DecisionResponse = DecisionResponse(requestFailure, answers)
    }
}

// A private object compiles to a package-private class, so these shared checks add nothing Java can see.
private object AnswerRules {

    /**
     * Checks that an answer name is not blank.
     *
     * @param name the answer name to check
     */
    fun requireName(name: String) {
        require(name.isNotBlank()) { "Answer name must not be blank" }
    }

    /**
     * Says how an answer differs from a question with the same name, or returns null when it fits.
     * Only the kind and the options or levels can be compared, because an answer holds no
     * instructions.
     *
     * @param answer the answer to check
     * @param question the question it should fit
     * @return why they differ, or null when the answer fits the question
     */
    fun mismatch(answer: DecisionAnswer, question: Question<*>): String? = when {
        answer.kind != question.kind ->
            "it is a ${answer.kind.wireName} answer and the question is a ${question.kind.wireName} question"
        answer is DecisionAnswer.Choice && question is ChoiceQuestionSpec ->
            entryMismatch("options", answer.options.map { it.id to it.description }, question.options.map { it.id to it.description })
        answer is DecisionAnswer.Rating && question is RatingQuestionSpec ->
            entryMismatch("levels", answer.levels.map { it.id to it.description }, question.levels.map { it.id to it.description })
        else -> null
    }

    /**
     * Compares options or levels as (id, description) pairs in order.
     *
     * @param label what these entries are called, "options" or "levels"
     * @param answer the answer's entries, in order
     * @param question the question's entries, in order
     * @return why they differ, or null when they match
     */
    private fun entryMismatch(label: String, answer: List<Pair<String, String>>, question: List<Pair<String, String>>): String? {
        if (answer == question) return null
        val answerIds = answer.map { it.first }
        val questionIds = question.map { it.first }
        if (answerIds != questionIds) {
            return "its $label are ${answerIds.joinToString { "'$it'" }} and the question's are ${questionIds.joinToString { "'$it'" }}"
        }
        val changed = answer.zip(question).filter { (a, q) -> a.second != q.second }.map { it.first.first }
        return "its $label have different descriptions from the question's for ${changed.joinToString { "'$it'" }}"
    }

    /**
     * Reads the failure reason out of an answer's outcome.
     *
     * @param answer the answer to check
     * @return the reason its outcome failed, or null when it did not fail
     */
    fun failureReason(answer: DecisionAnswer): FailureReason? = when (answer) {
        is DecisionAnswer.Proposition -> answer.outcome.let { if (it is PropositionResult.Failure) it.reason else null }
        is DecisionAnswer.Choice -> answer.outcome.let { if (it is ClassificationResult.Failure) it.reason else null }
        is DecisionAnswer.Rating -> answer.outcome.let { if (it is RatingResult.Failure) it.reason else null }
    }

    /**
     * Builds the failure answer for a question, in the outcome type its kind requires.
     *
     * @param question the question that failed
     * @param reason why it failed
     * @return the failure answer
     */
    fun failure(question: Question<*>, reason: FailureReason): DecisionAnswer = when (question) {
        is PropositionQuestionSpec -> DecisionAnswer.Proposition.create(question.name, PropositionResult.Failure(reason))
        is ChoiceQuestionSpec ->
            DecisionAnswer.Choice.create(question.name, question.options, ClassificationResult.Failure(reason))
        is RatingQuestionSpec ->
            DecisionAnswer.Rating.create(question.name, question.levels, RatingResult.Failure(reason))
    }
}
