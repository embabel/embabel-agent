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

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import org.jetbrains.annotations.ApiStatus
import java.util.Objects
import java.util.function.Consumer

/**
 * An immutable, ordered set of questions to put to a model in one decision. Question names are
 * unique within a spec and name the answers in a response.
 *
 * Declare the questions inline with [builder], or pass questions made with `Questions.named(...)`
 * to [of]. Both forms validate the same way and give equal specs for equal questions.
 *
 * ```java
 * var triage = DecisionSpec.builder()
 *     .proposition("is_urgent", question -> question
 *         .asking("Does this convey urgency?"))
 *     .choice("department", question -> question
 *         .asking("Which team should handle this?")
 *         .option("billing", "Payments, invoicing, refunds")
 *         .option("technical", "Bugs, outages, integrations"))
 *     .build();
 * ```
 *
 * Two specs are equal when they hold equal questions in the same order, whatever their class. A
 * `ClassificationSpec` is the one subclass: a spec with a single choice question.
 */
@ApiStatus.Experimental
open class DecisionSpec internal constructor(questions: List<Question<*>>) {

    /** The questions in declared order. The list cannot be modified. */
    @get:JsonProperty("questions")
    val questions: List<Question<*>> = java.util.List.copyOf(questions)

    init {
        require(this.questions.isNotEmpty()) { "A decision spec needs at least one question" }
        val seen = HashSet<String>()
        val repeated = this.questions.map { it.name }.filterNot(seen::add).distinct()
        require(repeated.isEmpty()) {
            "Question names must be unique within a decision spec. Repeated: ${repeated.joinToString { "'$it'" }}"
        }
    }

    /**
     * Returns the question with the given name.
     *
     * @param name the question name
     * @return the question, or null if this spec has no question with that name
     */
    fun question(name: String): Question<*>? = questions.firstOrNull { it.name == name }

    final override fun equals(other: Any?): Boolean =
        this === other || other is DecisionSpec && questions == other.questions

    final override fun hashCode(): Int = questions.hashCode()

    override fun toString(): String = "DecisionSpec(questions=${questions.map { it.name }})"

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionSpec", name, value)

    /**
     * Collects the questions of a decision spec in declared order. Get one from
     * [DecisionSpec.builder].
     *
     * Each declaring method either adds exactly one question or leaves the builder as it was.
     * When a method throws, nothing is added. Each call to [build] returns a new spec, and later
     * declarations do not affect specs already built. A builder is not safe for use from several
     * threads at once.
     */
    @ApiStatus.Experimental
    class Builder private constructor() {

        private val questions = ArrayList<Question<*>>()

        // True while a customizer is running, so a customizer cannot add to this builder mid-declaration.
        private var declaring = false

        /**
         * Declares a proposition question. The customizer receives a new builder that already
         * carries the name, and must at least call `asking(...)` on it. The customizer runs once,
         * before this method returns, and its exceptions pass through unchanged. It does not run
         * when the name is blank or already used.
         *
         * @param name the question name, which must not be blank or used by an earlier question
         * @param customizer sets the question's definition
         * @return this builder
         * @throws IllegalArgumentException if the name is blank or already used, or the definition is invalid
         * @throws IllegalStateException if called from inside a customizer running on this builder
         */
        fun proposition(name: String, customizer: Consumer<in PropositionQuestionSpec.Builder>): Builder =
            declare(name) { PropositionQuestionSpec.Builder.create(name).also(customizer::accept).build() }

        /**
         * Declares a choice question. The customizer receives a new builder that already carries
         * the name, and must call `asking(...)` and add at least one option. The customizer runs
         * once, before this method returns, and its exceptions pass through unchanged. It does not
         * run when the name is blank or already used.
         *
         * @param name the question name, which must not be blank or used by an earlier question
         * @param customizer sets the question's definition
         * @return this builder
         * @throws IllegalArgumentException if the name is blank or already used, or the definition is invalid
         * @throws IllegalStateException if called from inside a customizer running on this builder
         */
        fun choice(name: String, customizer: Consumer<in ChoiceQuestionSpec.Builder>): Builder =
            declare(name) { ChoiceQuestionSpec.Builder.create(name).also(customizer::accept).build() }

        /**
         * Declares a rating question. The customizer receives a new builder that already carries
         * the name, and must call `asking(...)` and add at least two levels, lowest first. The
         * customizer runs once, before this method returns, and its exceptions pass through
         * unchanged. It does not run when the name is blank or already used.
         *
         * @param name the question name, which must not be blank or used by an earlier question
         * @param customizer sets the question's definition
         * @return this builder
         * @throws IllegalArgumentException if the name is blank or already used, or the definition is invalid
         * @throws IllegalStateException if called from inside a customizer running on this builder
         */
        fun rating(name: String, customizer: Consumer<in RatingQuestionSpec.Builder>): Builder =
            declare(name) { RatingQuestionSpec.Builder.create(name).also(customizer::accept).build() }

        /**
         * Adds a question that was built earlier, for example with `Questions.named(...)`.
         *
         * @param question the question to add, whose name must not be used by an earlier question
         * @return this builder
         * @throws IllegalArgumentException if the name is already used
         * @throws IllegalStateException if called from inside a customizer running on this builder
         */
        fun question(question: Question<*>): Builder = declare(question.name) { question }

        /**
         * Returns a new spec holding the questions declared so far. The builder can keep declaring
         * questions afterwards.
         *
         * @throws IllegalArgumentException if no question has been declared
         */
        fun build(): DecisionSpec = DecisionSpec(questions)

        /**
         * Every declaring method goes through here. The question is appended only after make()
         * returns, and nothing else touches the list, so a failure anywhere leaves it as it was.
         *
         * @param name the question name, checked for blank or already used before make runs
         * @param make builds the question once the name checks pass
         * @return this builder
         */
        private inline fun declare(name: String, make: () -> Question<*>): Builder {
            check(!declaring) {
                "Cannot declare question '$name' while a customizer on this builder is running. " +
                    "Declare it after the enclosing customizer returns."
            }
            requireUnused(name)
            declaring = true
            val question = try {
                make()
            } finally {
                declaring = false
            }
            questions += question
            return this
        }

        /**
         * Checks that no question already declared has this name.
         *
         * @param name the question name to check
         */
        private fun requireUnused(name: String) {
            require(questions.none { it.name == name }) { "Question names must be unique within a decision spec. Repeated: '$name'" }
        }

        internal companion object {
            // Used by DecisionSpec.builder. Hidden from Java so a builder can only come from there.
            @JvmSynthetic
            internal fun create(): Builder = Builder()
        }
    }

    /**
     * Creates decision specs.
     */
    companion object {

        /**
         * Starts an empty builder.
         *
         * @return a new builder with no questions
         */
        @JvmStatic
        fun builder(): Builder = Builder.create()

        /**
         * Returns a spec holding the given questions in the given order.
         *
         * @param questions the questions, which must have unique names
         * @throws IllegalArgumentException if there are no questions or two share a name
         */
        @JvmStatic
        fun of(vararg questions: Question<*>): DecisionSpec = DecisionSpec(questions.asList())

        /**
         * Returns a spec holding the given questions in list order. Later changes to the list do not
         * affect the spec.
         *
         * @param questions the questions, which must have unique names
         * @throws IllegalArgumentException if there are no questions or two share a name
         */
        @JvmStatic
        fun of(questions: List<Question<*>>): DecisionSpec = DecisionSpec(questions)

        /**
         * Builds a spec from deserialized JSON fields.
         *
         * @param questions the questions as read from JSON
         * @return the spec
         */
        @JvmStatic
        @JsonCreator
        private fun fromJson(@JsonProperty("questions", required = true) questions: List<Question<*>>): DecisionSpec =
            DecisionSpec(questions)
    }
}

/**
 * One decision spec together with the input it should be evaluated against.
 *
 * @property input the text the model reasons over. It may be empty when a spec needs no input
 * beyond its questions.
 * @property spec the questions to answer
 *
 * Two requests are equal when their inputs and specs are equal, whatever their class. A
 * `ClassificationRequest` is the one subclass: a request whose spec is a `ClassificationSpec`.
 */
@ApiStatus.Experimental
@JsonPropertyOrder("input", "spec")
open class DecisionRequest internal constructor(
    @get:JsonProperty("input") val input: String,
    @get:JsonProperty("spec") open val spec: DecisionSpec,
) {

    final override fun equals(other: Any?): Boolean =
        this === other || other is DecisionRequest && input == other.input && spec == other.spec

    final override fun hashCode(): Int = Objects.hash(input, spec)

    /** Shows the spec only. The input is left out because it can be long or hold private text. */
    override fun toString(): String = "DecisionRequest(spec=$spec)"

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionRequest", name, value)

    /**
     * Creates decision requests.
     */
    companion object {

        /**
         * Returns a request that evaluates the given spec against the given input.
         *
         * @param input the text the model reasons over, which may be empty
         * @param spec the questions to answer
         * @return the request
         */
        @JvmStatic
        fun of(input: String, spec: DecisionSpec): DecisionRequest = DecisionRequest(input, spec)

        /**
         * Returns a request built from the given questions, in the given order.
         *
         * @param input the text the model reasons over, which may be empty
         * @param questions the questions to answer, which must have unique names
         * @return the request
         * @throws IllegalArgumentException if there are no questions or two share a name
         */
        @JvmStatic
        fun of(input: String, vararg questions: Question<*>): DecisionRequest =
            DecisionRequest(input, DecisionSpec.of(*questions))

        /**
         * Builds a request from deserialized JSON fields.
         *
         * @param input the text the model reasons over
         * @param spec the questions to answer
         * @return the request
         */
        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("input", required = true) input: String,
            @JsonProperty("spec", required = true) spec: DecisionSpec,
        ): DecisionRequest = DecisionRequest(input, spec)
    }
}
