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
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import org.jetbrains.annotations.ApiStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.annotation.JsonDeserialize
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
 * The input is either text or a structured state, which is a JSON object or array. A service that
 * speaks System One sends a state as JSON, so questions can point at values in it with backticked
 * paths such as `` `intent.subject` ``. Every other service reads [input], which for a structured
 * request is the state written out as JSON text.
 *
 * As JSON, a text request is `{"input", "spec"}` and a structured one is `{"state", "spec"}`.
 * Reading takes exactly one of the two, and a `state` of null counts as missing.
 *
 * @property input the text the model reasons over. It may be empty when a spec needs no input
 * beyond its questions. For a structured request it is the state as compact JSON text, with
 * fields and elements in the order they were added.
 * @property spec the questions to answer
 *
 * Two requests are equal when their inputs, states and specs are equal, whatever their class. A
 * text request never equals a structured one, even when its text is the same JSON. A
 * `ClassificationRequest` is the one subclass: a request whose spec is a `ClassificationSpec`.
 */
@ApiStatus.Experimental
@JsonPropertyOrder("input", "state", "spec")
@JsonDeserialize(using = DecisionRequestDeserializer::class)
open class DecisionRequest private constructor(
    @get:JsonIgnore val input: String,
    @get:JsonProperty("spec") open val spec: DecisionSpec,
    // The request's own copy of the state. Nothing outside this class can reach it.
    private val structured: JsonNode?,
) {

    internal constructor(input: String, spec: DecisionSpec) : this(input, spec, null)

    // A classification request is built through this one, so it checks and copies the state the same way.
    internal constructor(state: JsonNode, spec: DecisionSpec) : this(structuredSnapshot(state), spec, Unit)

    // The last parameter only tells this constructor apart from the others.
    private constructor(snapshot: JsonNode, spec: DecisionSpec, @Suppress("UNUSED_PARAMETER") own: Unit) :
        this(snapshot.toString(), spec, snapshot)

    /**
     * The structured state the model reasons over, or null for a text request. Each call returns a
     * fresh copy, so changing it does not change the request.
     */
    @get:JsonProperty("state")
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val state: JsonNode? get() = structured?.deepCopy()

    /**
     * The input as it is written to JSON. A structured request leaves it out, because its state
     * already says the same thing. It is not private, so a mapper that may not open private
     * members can still write a request.
     */
    @JsonProperty("input")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    internal fun writtenInput(): String? = input.takeIf { structured == null }

    final override fun equals(other: Any?): Boolean =
        this === other ||
            other is DecisionRequest && input == other.input && structured == other.structured && spec == other.spec

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
         * Returns a request that evaluates the given spec against a structured state. The request
         * keeps its own snapshot of the state as plain JSON, with fields and elements in the order
         * they were added, so later changes to the node or to anything it holds do not affect it.
         * A binary value becomes its Base64 text and an embedded object becomes the JSON it
         * serializes to.
         *
         * @param state the JSON object or array the model reasons over
         * @param spec the questions to answer
         * @return the request
         * @throws IllegalArgumentException if the state is not a JSON object or array, or cannot be
         * written as JSON
         */
        @JvmStatic
        fun of(state: JsonNode, spec: DecisionSpec): DecisionRequest = DecisionRequest(state, spec)

        /**
         * Returns a request built from a structured state and the given questions, in the given order.
         *
         * @param state the JSON object or array the model reasons over
         * @param questions the questions to answer, which must have unique names
         * @return the request
         * @throws IllegalArgumentException if the state is not a JSON object or array, there are no
         * questions, or two share a name
         */
        @JvmStatic
        fun of(state: JsonNode, vararg questions: Question<*>): DecisionRequest = of(state, DecisionSpec.of(*questions))
    }
}
