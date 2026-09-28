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

import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.rejectUnknownMember
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import org.jetbrains.annotations.ApiStatus

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
