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
import com.fasterxml.jackson.annotation.JsonValue
import org.jetbrains.annotations.ApiStatus

/**
 * How many questions of one kind a service will take in a single request.
 *
 * @property min the fewest questions of that kind a request may hold, at least 1
 * @property max the most questions of that kind a request may hold, at least [min]
 * @throws IllegalArgumentException if min is below 1 or above max
 */
@ApiStatus.Experimental
data class DecisionArity @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
    @param:JsonProperty("min", required = true) @get:JsonProperty("min")
    val min: Int,
    @param:JsonProperty("max", required = true) @get:JsonProperty("max")
    val max: Int,
) {
    init {
        require(min >= 1) { "Arity minimum must be at least 1, was $min" }
        require(min <= max) { "Arity minimum $min must not be above the maximum $max" }
    }

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionArity", name, value)
}

/**
 * Names what a provider's reported confidence actually measures, such as how concentrated its
 * distribution was. Two providers can both report a confidence of 0.8 and mean different
 * things, so a service states the measure it uses. In JSON it is just the id string.
 *
 * @property id the measure's name, for example `acme.concentration`
 * @throws IllegalArgumentException if the id is blank
 */
@ApiStatus.Experimental
data class ConfidenceMeasure @JsonCreator(mode = JsonCreator.Mode.DELEGATING) constructor(
    @get:JsonValue val id: String,
) {
    init {
        require(id.isNotBlank()) { "Confidence measure id must not be blank" }
    }
}

/**
 * The kinds of evidence a service can put in its answers. A service lists, per question kind,
 * the ones it actually reports.
 */
@ApiStatus.Experimental
enum class DecisionEvidenceKind {

    /** A probability that a proposition is true. */
    @JsonProperty("true_probability")
    TRUE_PROBABILITY,

    /** A probability for each option of a choice question. */
    @JsonProperty("category_distribution")
    CATEGORY_DISTRIBUTION,

    /** A probability for each level of a rating question. */
    @JsonProperty("level_distribution")
    LEVEL_DISTRIBUTION,

    /** The expected position on a rating scale, as a fractional level index. */
    @JsonProperty("expected_level_index")
    EXPECTED_LEVEL_INDEX,

    /** A confidence figure, whose meaning the service's confidence measure states. */
    @JsonProperty("confidence")
    CONFIDENCE,

    /** An explicit refusal to answer a question. */
    @JsonProperty("refusal")
    REFUSAL,
}

/**
 * Marks the receiver of the Kotlin capabilities block, so a call inside it cannot reach an outer
 * block's receiver by accident.
 */
@ApiStatus.Experimental
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class DecisionCapabilitiesDsl
