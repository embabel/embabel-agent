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
package com.embabel.common.ai.classification

import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.Question
import com.embabel.common.ai.decision.rejectUnknownMember
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import org.jetbrains.annotations.ApiStatus

/**
 * A classification: one choice question that picks a category for some text. It is a decision
 * spec with exactly one question, so a decision service can answer it and a decision response
 * holds its result under [question].
 *
 * ```java
 * ClassificationSpec departments = ClassificationSpec.builder()
 *     .asking("Which team should handle this?")
 *     .category("billing", "Payments, invoicing, refunds")
 *     .category("technical", "Bugs, outages, integrations")
 *     .build();
 * ```
 *
 * It equals any decision spec that holds the same single question, and its JSON is the decision
 * spec JSON.
 */
@ApiStatus.Experimental
@JsonAutoDetect(getterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE)
class ClassificationSpec private constructor(
    /** The one choice question. Its answer is the classification result. */
    val question: ChoiceQuestionSpec,
) : DecisionSpec(listOf(question)) {

    /** What the model is asked. */
    val instructions: String get() = question.instructions

    /** The categories to choose from, in declared order. The list cannot be modified. */
    val categories: List<Category> get() = question.options

    /**
     * Returns a selection of one of this spec's categories.
     *
     * @param categoryId the selected category's id
     * @param provenance the model that made the selection
     * @param confidence the provider's confidence, if it reported one
     * @return the selection
     * @throws IllegalArgumentException if the id is not one of the categories
     */
    @JvmOverloads
    fun selected(
        categoryId: String,
        provenance: ModelProvenance,
        confidence: Double? = null,
    ): ClassificationResult.Selected {
        val result = ClassificationResult.Selected(categoryId, provenance, confidence)
        validate(result)
        return result
    }

    /**
     * Checks that a result fits this spec. A selection must name one of the categories. Other
     * outcomes always fit.
     *
     * @param result the result to check
     * @return the result, unchanged
     * @throws IllegalArgumentException if a selection names an unknown category
     */
    fun validate(result: ClassificationResult): ClassificationResult = question.validate(result)

    override fun toString(): String = "ClassificationSpec(question=${question.name})"

    /**
     * Collects the instructions and categories of a classification spec. Get one from
     * [ClassificationSpec.builder]. A builder is not safe for use from several threads at once.
     */
    @ApiStatus.Experimental
    class Builder private constructor() {

        private val choice = ChoiceQuestionSpec.Builder.create(QUESTION_NAME)

        /**
         * Sets what the model is asked.
         *
         * @param instructions the question text, which must not be blank
         * @return this builder
         */
        fun asking(instructions: String): Builder = apply { choice.asking(instructions) }

        /**
         * Adds a category.
         *
         * @param id the category id, which must not be blank or used by an earlier category
         * @param description when the category applies
         * @return this builder
         */
        fun category(id: String, description: String): Builder = apply { choice.option(id, description) }

        /**
         * Returns a new spec from what has been set so far.
         *
         * @return the spec
         * @throws IllegalArgumentException if the instructions are missing or blank, there are no
         * categories, or two categories share an id
         */
        fun build(): ClassificationSpec = ClassificationSpec(choice.build())

        internal companion object {
            // Used by ClassificationSpec.builder. Hidden from Java so a builder can only come from there.
            @JvmSynthetic
            internal fun create(): Builder = Builder()
        }
    }

    /**
     * Creates classification specs.
     */
    companion object {

        /** The name the builder gives the choice question. */
        const val QUESTION_NAME: String = "classification"

        /**
         * Starts an empty builder. Its question is named [QUESTION_NAME].
         *
         * @return a new builder
         */
        @JvmStatic
        fun builder(): Builder = Builder.create()

        /**
         * Returns a spec that wraps an existing choice question, keeping its name.
         *
         * @param question the choice question
         * @return the spec
         */
        @JvmStatic
        fun of(question: ChoiceQuestionSpec): ClassificationSpec = ClassificationSpec(question)

        /**
         * Builds a spec from deserialized JSON fields.
         *
         * @param questions the questions as read from JSON, which must be a single choice question
         * @return the spec
         */
        @JvmStatic
        @JsonCreator
        private fun fromJson(@JsonProperty("questions", required = true) questions: List<Question<*>>): ClassificationSpec {
            val question = questions.singleOrNull()
            require(question is ChoiceQuestionSpec) { "A classification spec needs exactly one choice question" }
            return ClassificationSpec(question)
        }
    }
}

/** A nonblank canonical category ID and its natural-language meaning, including caller-defined aliases. */
@ApiStatus.Experimental
@JsonPropertyOrder("id", "description")
data class Category @JsonCreator constructor(
    @JsonProperty("id", required = true) val id: String,
    @JsonProperty("description", required = true) val description: String,
) {
    init {
        require(id.isNotBlank()) { "Category ID must not be blank" }
    }

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("Category", name, value)
}

/**
 * Text to classify with a [ClassificationSpec]. A classification request is a decision request whose
 * spec has one choice question, so it goes anywhere a [DecisionRequest] goes. Its JSON is the
 * decision request JSON. The input may be empty.
 *
 * @property spec the classification to make
 */
@ApiStatus.Experimental
@JsonAutoDetect(getterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE)
class ClassificationRequest private constructor(
    input: String,
    @get:JsonProperty("spec") override val spec: ClassificationSpec,
) : DecisionRequest(input, spec) {

    /** The categories to choose from, in the spec's order. */
    val categories: List<Category> get() = spec.categories

    /** What the model is asked, from the spec. */
    val instructions: String get() = spec.instructions

    /** Shows the spec only. The input is left out because it can be long or hold private text. */
    override fun toString(): String = "ClassificationRequest(spec=$spec)"

    /**
     * Creates classification requests.
     */
    companion object {

        /**
         * Returns a request that classifies the input with the given spec.
         *
         * @param input the text to classify, which may be empty
         * @param spec the classification to make
         * @return the request
         */
        @JvmStatic
        fun of(input: String, spec: ClassificationSpec): ClassificationRequest = ClassificationRequest(input, spec)

        /**
         * Builds a request from deserialized JSON fields.
         *
         * @param input the text to classify
         * @param spec the classification to make
         * @return the request
         */
        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("input", required = true) input: String,
            @JsonProperty("spec", required = true) spec: ClassificationSpec,
        ): ClassificationRequest = ClassificationRequest(input, spec)
    }
}

/**
 * Builds a classification spec with a Kotlin receiver block.
 *
 * ```kotlin
 * val departments = classificationSpec {
 *     asking("Which team should handle this?")
 *     category("billing", "Payments, invoicing, refunds")
 * }
 * ```
 *
 * @param block sets the instructions and categories
 * @return the built spec
 * @throws IllegalArgumentException if the instructions are missing or blank, there are no
 * categories, or two categories share an id
 */
@ApiStatus.Experimental
@JvmSynthetic
fun classificationSpec(block: ClassificationSpec.Builder.() -> Unit): ClassificationSpec =
    ClassificationSpec.builder().apply(block).build()
