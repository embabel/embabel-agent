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
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import org.jetbrains.annotations.ApiStatus
import kotlin.math.abs

/** One provider-reported probability, without a calibration claim. */
@ApiStatus.Experimental
@JsonPropertyOrder("categoryId", "probability")
data class CategoryProbability @JsonCreator constructor(
    @JsonProperty("categoryId", required = true) val categoryId: String,
    @JsonProperty("probability", required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    val probability: Double,
) {
    init {
        require(categoryId.isNotBlank()) { "Category id must not be blank" }
        require(probability.isFinite() && probability in 0.0..1.0) {
            "Probability must be finite and between 0 and 1"
        }
    }

    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing =
        rejectUnknownMember("CategoryProbability", name, value)
}

/** Complete choice evidence in the question's option order. Values are never normalized or inferred. */
@ApiStatus.Experimental
@JsonPropertyOrder("probabilities")
class ChoiceDistribution private constructor(probabilities: List<CategoryProbability>) {
    @get:JsonProperty("probabilities")
    val probabilities: List<CategoryProbability> = java.util.List.copyOf(probabilities)

    init {
        require(this.probabilities.isNotEmpty()) { "A choice distribution needs at least one probability" }
        val ids = this.probabilities.map { it.categoryId }
        require(ids.size == ids.toSet().size) { "Choice probability ids must be unique" }
        require(abs(this.probabilities.sumOf { it.probability } - 1.0) <= 1e-6) {
            "Choice probabilities must sum to 1 within 1e-6"
        }
    }

    @JvmSynthetic
    internal fun orderedFor(options: List<Category>): ChoiceDistribution {
        val ids = options.map { it.id }
        require(ids.isNotEmpty() && ids.size == ids.toSet().size) { "Choice options must be nonempty and unique" }
        require(probabilities.map { it.categoryId }.toSet() == ids.toSet()) {
            "Choice distribution must cover exactly the question's options"
        }
        val byId = probabilities.associateBy { it.categoryId }
        return ChoiceDistribution(ids.map { byId.getValue(it) })
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ChoiceDistribution && probabilities == other.probabilities

    override fun hashCode(): Int = probabilities.hashCode()

    override fun toString(): String = "ChoiceDistribution(categories=${probabilities.size})"

    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("ChoiceDistribution", name, value)

    companion object {
        /** Reject incomplete evidence and copy its entries into the declared domain order. */
        @JvmStatic
        fun of(options: List<Category>, probabilities: List<CategoryProbability>): ChoiceDistribution =
            ChoiceDistribution(probabilities).orderedFor(options)

        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("probabilities", required = true)
            @JsonFormat(without = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
            probabilities: List<CategoryProbability>,
        ): ChoiceDistribution = ChoiceDistribution(probabilities)
    }
}
