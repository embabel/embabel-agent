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
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.jetbrains.annotations.ApiStatus
import java.util.Objects

/**
 * Marks the receiver types of the Kotlin decision spec DSL. Inside a block with a marked
 * receiver, calls cannot reach an outer marked receiver without an explicit label, so a question
 * block cannot add a question to the enclosing spec by accident.
 */
@ApiStatus.Experimental
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class DecisionSpecDsl

/**
 * The closed set of question kinds. Each kind fixes the result type of its questions.
 */
@ApiStatus.Experimental
enum class QuestionKind(
    // The lower-case name used on the wire and in error messages.
    @get:JvmSynthetic internal val wireName: String,
) {
    /** A true-or-false question, answered with a [PropositionResult]. */
    @JsonProperty("proposition")
    PROPOSITION("proposition"),

    /** A pick-one question over a closed set of options, answered with a [ClassificationResult]. */
    @JsonProperty("choice")
    CHOICE("choice"),

    /** A question over an ordered scale of levels, answered with a [RatingResult]. */
    @JsonProperty("rating")
    RATING("rating"),
}

/**
 * An immutable question definition whose answers have type [R]. The three implementations are
 * [PropositionQuestionSpec], [ChoiceQuestionSpec] and [RatingQuestionSpec], and no other
 * implementation can exist.
 *
 * Build a question with `Questions.named(...)`, or declare it inside a decision spec.
 *
 * In JSON a question is an object whose `kind` member names its class.
 */
@ApiStatus.Experimental
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(PropositionQuestionSpec::class, name = "proposition"),
    JsonSubTypes.Type(ChoiceQuestionSpec::class, name = "choice"),
    JsonSubTypes.Type(RatingQuestionSpec::class, name = "rating"),
)
@JsonPropertyOrder("kind", "name", "instructions", "options", "levels")
sealed interface Question<out R : Any> {

    /** The caller-owned name of the question. It is unique within a spec and names the answer in a response. */
    @get:JsonProperty("name")
    val name: String

    /** The text that tells the model what to decide. */
    @get:JsonProperty("instructions")
    val instructions: String

    /** The kind of the question, which matches its result type. */
    @get:JsonProperty("kind")
    val kind: QuestionKind
}

/**
 * A question that asks the model whether a proposition is true.
 */
@ApiStatus.Experimental
class PropositionQuestionSpec private constructor(
    override val name: String,
    override val instructions: String,
) : Question<PropositionResult> {

    override val kind: QuestionKind get() = QuestionKind.PROPOSITION

    override fun equals(other: Any?): Boolean =
        this === other || other is PropositionQuestionSpec && name == other.name && instructions == other.instructions

    override fun hashCode(): Int = Objects.hash(kind, name, instructions)

    override fun toString(): String = "PropositionQuestionSpec(name=$name, kind=$kind)"

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("PropositionQuestionSpec", name, value)

    private companion object {
        /**
         * Reads a question from JSON through its builder, which runs the same checks.
         *
         * @param name the question name
         * @param instructions the text that tells the model what to decide
         * @return the question
         */
        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("name", required = true) name: String,
            @JsonProperty("instructions", required = true) instructions: String,
        ): PropositionQuestionSpec = Builder.create(name).asking(instructions).build()
    }

    /**
     * Collects the definition of one proposition question. Each call to [build] returns a new
     * question, and later changes to the builder do not affect questions it already built.
     * A builder is not safe for use from several threads at once.
     */
    @ApiStatus.Experimental
    @DecisionSpecDsl
    class Builder private constructor(private val name: String) {

        private var instructions: String? = null

        init {
            QuestionRules.requireName(name)
        }

        /**
         * Sets the instructions, replacing any set earlier.
         *
         * @param instructions the text that tells the model what to decide, which must not be blank
         * @return this builder
         */
        fun asking(instructions: String): Builder = apply { this.instructions = instructions }

        /**
         * Returns a new question with the current definition.
         *
         * @throws IllegalArgumentException if the instructions are missing or blank
         */
        fun build(): PropositionQuestionSpec =
            PropositionQuestionSpec(name, QuestionRules.requireInstructions(name, instructions))

        internal companion object {
            // Used by DecisionSpec and Questions. Hidden from Java so a builder can only come from those.
            @JvmSynthetic
            internal fun create(name: String): Builder = Builder(name)
        }
    }
}

/**
 * A question that asks the model to pick one option from a closed set.
 */
@ApiStatus.Experimental
class ChoiceQuestionSpec private constructor(
    override val name: String,
    override val instructions: String,
    options: List<Category>,
) : Question<ClassificationResult> {

    /** The options in declared order. The list cannot be modified. Option ids are unique. */
    @get:JsonIgnore
    val options: List<Category> = java.util.List.copyOf(options)

    override val kind: QuestionKind get() = QuestionKind.CHOICE

    /**
     * Checks that a result fits this question and returns it unchanged. A selection must name one
     * of the options. Results without a selection always fit.
     *
     * @throws IllegalArgumentException if the selected category id is not one of the options
     */
    fun validate(result: ClassificationResult): ClassificationResult = OutcomeRules.fitChoice(name, options, result)

    override fun equals(other: Any?): Boolean =
        this === other || other is ChoiceQuestionSpec &&
            name == other.name && instructions == other.instructions && options == other.options

    override fun hashCode(): Int = Objects.hash(kind, name, instructions, options)

    override fun toString(): String = "ChoiceQuestionSpec(name=$name, kind=$kind)"

    /**
     * Converts the options to their JSON form.
     *
     * @return the options as JSON
     */
    @JsonProperty("options")
    private fun optionsJson(): List<OptionJson> = options.map(::OptionJson)

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("ChoiceQuestionSpec", name, value)

    private companion object {
        /**
         * Reads a question from JSON through its builder, which runs the same checks.
         *
         * @param name the question name
         * @param instructions the text that tells the model what to decide
         * @param options the options as read from JSON
         * @return the question
         */
        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("name", required = true) name: String,
            @JsonProperty("instructions", required = true) instructions: String,
            @JsonProperty("options", required = true) options: List<OptionJson>,
        ): ChoiceQuestionSpec = Builder.create(name).asking(instructions)
            .apply { options.forEach { option(it.id, it.description) } }
            .build()
    }

    /**
     * Collects the definition of one choice question. Each call to [build] returns a new question,
     * and later changes to the builder do not affect questions it already built. A builder is not
     * safe for use from several threads at once.
     */
    @ApiStatus.Experimental
    @DecisionSpecDsl
    class Builder private constructor(private val name: String) {

        private var instructions: String? = null
        private val options = ArrayList<Pair<String, String>>()

        init {
            QuestionRules.requireName(name)
        }

        /**
         * Sets the instructions, replacing any set earlier.
         *
         * @param instructions the text that tells the model what to decide, which must not be blank
         * @return this builder
         */
        fun asking(instructions: String): Builder = apply { this.instructions = instructions }

        /**
         * Adds an option after those already added.
         *
         * @param id the option id the model answers with, which must not be blank and must be unique
         * @param description what the option means
         * @return this builder
         */
        fun option(id: String, description: String): Builder = apply { options += id to description }

        /**
         * Returns a new question with the current definition.
         *
         * @throws IllegalArgumentException if the instructions are missing or blank, there are no
         * options, or an option id is blank or repeated
         */
        fun build(): ChoiceQuestionSpec {
            val text = QuestionRules.requireInstructions(name, instructions)
            OutcomeRules.requireOptionIds(name, options.map { it.first })
            return ChoiceQuestionSpec(name, text, options.map { (id, description) -> Category(id, description) })
        }

        internal companion object {
            // Used by DecisionSpec and Questions. Hidden from Java so a builder can only come from those.
            @JvmSynthetic
            internal fun create(name: String): Builder = Builder(name)
        }
    }
}

/**
 * A question that asks the model to rate something on an ordered scale of levels. The first level
 * has index 0 and the last has index `levels.size - 1`.
 */
@ApiStatus.Experimental
class RatingQuestionSpec private constructor(
    override val name: String,
    override val instructions: String,
    levels: List<RatingLevel>,
) : Question<RatingResult> {

    /** The levels from lowest to highest. The list cannot be modified. Level ids are unique. */
    @get:JsonProperty("levels")
    val levels: List<RatingLevel> = java.util.List.copyOf(levels)

    override val kind: QuestionKind get() = QuestionKind.RATING

    /**
     * Checks that a result fits this question's scale and returns it unchanged. A selected level
     * must be one of the levels. A distribution must cover exactly the levels, in any order.
     * A score must not exceed the index of the last level. Results without evidence always fit.
     *
     * @throws IllegalArgumentException if the evidence does not fit the scale
     */
    fun validate(result: RatingResult): RatingResult = OutcomeRules.fitRating(name, levels, result)

    override fun equals(other: Any?): Boolean =
        this === other || other is RatingQuestionSpec &&
            name == other.name && instructions == other.instructions && levels == other.levels

    override fun hashCode(): Int = Objects.hash(kind, name, instructions, levels)

    override fun toString(): String = "RatingQuestionSpec(name=$name, kind=$kind)"

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("RatingQuestionSpec", name, value)

    private companion object {
        /**
         * Reads a question from JSON through its builder, which runs the same checks.
         *
         * @param name the question name
         * @param instructions the text that tells the model what to decide
         * @param levels the levels as read from JSON
         * @return the question
         */
        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("name", required = true) name: String,
            @JsonProperty("instructions", required = true) instructions: String,
            @JsonProperty("levels", required = true) levels: List<RatingLevel>,
        ): RatingQuestionSpec = Builder.create(name).asking(instructions)
            .apply { levels.forEach { level(it.id, it.description) } }
            .build()
    }

    /**
     * Collects the definition of one rating question. Levels are added from lowest to highest.
     * Each call to [build] returns a new question, and later changes to the builder do not affect
     * questions it already built. A builder is not safe for use from several threads at once.
     */
    @ApiStatus.Experimental
    @DecisionSpecDsl
    class Builder private constructor(private val name: String) {

        private var instructions: String? = null
        private val levels = ArrayList<Pair<String, String>>()

        init {
            QuestionRules.requireName(name)
        }

        /**
         * Sets the instructions, replacing any set earlier.
         *
         * @param instructions the text that tells the model what to decide, which must not be blank
         * @return this builder
         */
        fun asking(instructions: String): Builder = apply { this.instructions = instructions }

        /**
         * Adds a level above those already added, using the label as both its id and description.
         *
         * @param label the level's id and description, which must not be blank and must be unique
         * @return this builder
         */
        fun level(label: String): Builder = level(label, label)

        /**
         * Adds a level above those already added.
         *
         * @param id the level id the model answers with, which must not be blank and must be unique
         * @param description what the level means
         * @return this builder
         */
        fun level(id: String, description: String): Builder = apply { levels += id to description }

        /**
         * Returns a new question with the current definition.
         *
         * @throws IllegalArgumentException if the instructions are missing or blank, there are
         * fewer than two levels, or a level id is blank or repeated
         */
        fun build(): RatingQuestionSpec {
            val text = QuestionRules.requireInstructions(name, instructions)
            OutcomeRules.requireLevelIds(name, levels.map { it.first })
            return RatingQuestionSpec(name, text, levels.map { (id, description) -> RatingLevel(id, description) })
        }

        internal companion object {
            // Used by DecisionSpec and Questions. Hidden from Java so a builder can only come from those.
            @JvmSynthetic
            internal fun create(name: String): Builder = Builder(name)
        }
    }
}

// A private object compiles to a package-private class, so these shared checks add nothing Java can see.
private object QuestionRules {

    fun requireName(name: String) {
        require(name.isNotBlank()) { "Question name must not be blank" }
    }

    fun requireInstructions(name: String, instructions: String?): String {
        require(instructions != null) { "Question '$name': instructions are missing. Call asking(...) before build()." }
        require(instructions.isNotBlank()) { "Question '$name': instructions must not be blank" }
        return instructions
    }
}

// The one copy of the option, level and outcome rules. Question builders, the question validators
// and the answers in a decision response all call these, so every path gives the same messages.
internal object OutcomeRules {

    /** Requires at least one option, and option ids that are nonblank and unique. */
    fun requireOptionIds(name: String, ids: List<String>) {
        require(ids.isNotEmpty()) { "Question '$name': at least one option is required" }
        requireEntryIds(name, "option", ids)
    }

    /** Requires at least two levels, and level ids that are nonblank and unique. */
    fun requireLevelIds(name: String, ids: List<String>) {
        require(ids.size >= 2) { "Question '$name': at least two levels are required" }
        requireEntryIds(name, "level", ids)
    }

    /** Returns the result unchanged if any selection names one of the options. */
    fun fitChoice(name: String, options: List<Category>, result: ClassificationResult): ClassificationResult {
        require(result !is ClassificationResult.Selected || options.any { it.id == result.categoryId }) {
            "Question '$name': the selected category id is not one of its options"
        }
        return result
    }

    /**
     * Returns the result unchanged if its evidence fits the levels: a selected level is one of
     * them, a distribution covers exactly them, and a score is at most the last level index.
     */
    fun fitRating(name: String, levels: List<RatingLevel>, result: RatingResult): RatingResult {
        if (result is RatingResult.Answered) {
            val levelIds = levels.map { it.id }.toSet()
            require(result.selectedLevelId == null || result.selectedLevelId in levelIds) {
                "Question '$name': the selected level id is not one of its levels"
            }
            require(result.distribution.isEmpty() || result.distribution.map { it.levelId }.toSet() == levelIds) {
                "Question '$name': the distribution must cover exactly its levels"
            }
            val score = result.score
            require(score == null || score.value <= levels.size - 1) {
                "Question '$name': the score ${score?.value} is above the last level index ${levels.size - 1}"
            }
        }
        return result
    }

    /**
     * Requires every id to be nonblank and unique.
     *
     * @param name the question name, used in error messages
     * @param entry what these ids are called, "option" or "level"
     * @param ids the ids to check
     */
    private fun requireEntryIds(name: String, entry: String, ids: List<String>) {
        require(ids.none { it.isBlank() }) { "Question '$name': $entry id must not be blank" }
        val seen = HashSet<String>()
        val repeated = ids.filterNot(seen::add).distinct()
        require(repeated.isEmpty()) {
            "Question '$name': $entry ids must be unique. Repeated: ${repeated.joinToString { "'$it'" }}"
        }
    }
}
