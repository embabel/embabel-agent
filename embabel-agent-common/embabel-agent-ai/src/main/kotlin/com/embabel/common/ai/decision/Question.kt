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
    // The lower-case name used on the wire and in definition ids. Every writer reads it from here.
    @get:JvmSynthetic internal val wireName: String,
) {
    /** A true-or-false question, answered with a [PropositionResult]. */
    PROPOSITION("proposition"),

    /** A pick-one question over a closed set of options, answered with a [ClassificationResult]. */
    CHOICE("choice"),

    /** A question over an ordered scale of levels, answered with a [RatingResult]. */
    RATING("rating"),
}

/**
 * An immutable question definition whose answers have type [R]. The three implementations are
 * [PropositionQuestionSpec], [ChoiceQuestionSpec] and [RatingQuestionSpec], and no other
 * implementation can exist.
 *
 * Build a question with `Questions.named(...)`, or declare it inside a decision spec.
 */
@ApiStatus.Experimental
sealed interface Question<out R : Any> {

    /** The caller-owned name of the question. It is unique within a spec and names the answer in a response. */
    val name: String

    /** The text that tells the model what to decide. */
    val instructions: String

    /** The kind of the question, which matches its result type. */
    val kind: QuestionKind

    /**
     * The stable `d1-` id of this definition. It changes whenever the kind, name, instructions or
     * any option or level changes, including their order. Equal definitions have equal ids.
     */
    val definitionId: String
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

    override val definitionId: String = DefinitionIds.question(kind.wireName, name, instructions, emptyList())

    override fun equals(other: Any?): Boolean =
        this === other || other is PropositionQuestionSpec && name == other.name && instructions == other.instructions

    override fun hashCode(): Int = Objects.hash(kind, name, instructions)

    override fun toString(): String = "PropositionQuestionSpec(name=$name, kind=$kind)"

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
    val options: List<Category> = java.util.List.copyOf(options)

    override val kind: QuestionKind get() = QuestionKind.CHOICE

    override val definitionId: String =
        DefinitionIds.question(kind.wireName, name, instructions, this.options.map { it.id to it.description })

    /**
     * Checks that a result fits this question and returns it unchanged. A selection must name one
     * of the options. Results without a selection always fit.
     *
     * @throws IllegalArgumentException if the selected category id is not one of the options
     */
    fun validate(result: ClassificationResult): ClassificationResult {
        require(result !is ClassificationResult.Selected || options.any { it.id == result.categoryId }) {
            "Question '$name': the selected category id is not one of its options"
        }
        return result
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ChoiceQuestionSpec &&
            name == other.name && instructions == other.instructions && options == other.options

    override fun hashCode(): Int = Objects.hash(kind, name, instructions, options)

    override fun toString(): String = "ChoiceQuestionSpec(name=$name, kind=$kind)"

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
            require(options.isNotEmpty()) { "Question '$name': at least one option is required" }
            QuestionRules.requireEntryIds(name, "option", options)
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
    val levels: List<RatingLevel> = java.util.List.copyOf(levels)

    override val kind: QuestionKind get() = QuestionKind.RATING

    override val definitionId: String =
        DefinitionIds.question(kind.wireName, name, instructions, this.levels.map { it.id to it.description })

    /**
     * Checks that a result fits this question's scale and returns it unchanged. A selected level
     * must be one of the levels. A distribution must cover exactly the levels, in any order.
     * A score must not exceed the index of the last level. Results without evidence always fit.
     *
     * @throws IllegalArgumentException if the evidence does not fit the scale
     */
    fun validate(result: RatingResult): RatingResult {
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

    override fun equals(other: Any?): Boolean =
        this === other || other is RatingQuestionSpec &&
            name == other.name && instructions == other.instructions && levels == other.levels

    override fun hashCode(): Int = Objects.hash(kind, name, instructions, levels)

    override fun toString(): String = "RatingQuestionSpec(name=$name, kind=$kind)"

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
            require(levels.size >= 2) { "Question '$name': at least two levels are required" }
            QuestionRules.requireEntryIds(name, "level", levels)
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

    fun requireEntryIds(name: String, entry: String, entries: List<Pair<String, String>>) {
        require(entries.none { it.first.isBlank() }) { "Question '$name': $entry id must not be blank" }
        val seen = HashSet<String>()
        val repeated = entries.map { it.first }.filterNot(seen::add).distinct()
        require(repeated.isEmpty()) {
            "Question '$name': $entry ids must be unique. Repeated: ${repeated.joinToString { "'$it'" }}"
        }
    }
}
