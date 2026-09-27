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

/**
 * One decision spec together with the input it should be evaluated against.
 *
 * @property input the text the model reasons over. It may be empty when a spec needs no input
 * beyond its questions.
 * @property spec the questions to answer
 */
@ApiStatus.Experimental
@JsonPropertyOrder("input", "spec")
class DecisionRequest private constructor(
    @get:JsonProperty("input") val input: String,
    @get:JsonProperty("spec") val spec: DecisionSpec,
) {

    override fun equals(other: Any?): Boolean =
        this === other || other is DecisionRequest && input == other.input && spec == other.spec

    override fun hashCode(): Int = Objects.hash(input, spec)

    /** Shows the spec only. The input is left out because it can be long or hold private text. */
    override fun toString(): String = "DecisionRequest(spec=$spec)"

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

        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("input", required = true) input: String,
            @JsonProperty("spec", required = true) spec: DecisionSpec,
        ): DecisionRequest = DecisionRequest(input, spec)
    }
}
