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
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.EnumSet
import java.util.Objects

/**
 * What a decision service can accept and how it can run a request. This is information a service
 * reports about itself; it does not run anything on its own.
 *
 * Start from [of] with the supported question kinds and execution modes, then add any known limits
 * with [withMaxQuestions] and [withMaxInputCharacters]. Each call returns new capabilities.
 *
 * A null limit means the service does not report that limit. The service may still have one.
 *
 * The kinds and modes always iterate in the order their enums declare them, so `toString` and any
 * serialized form come out the same on every run.
 *
 * @property maxQuestions the largest number of questions a request may hold, or null when the
 * service does not report a limit
 * @property maxInputCharacters the largest input length in characters, or null when the service
 * does not report a limit
 */
@ApiStatus.Experimental
@JsonPropertyOrder("questionKinds", "executionModes", "maxQuestions", "maxInputCharacters")
@JsonInclude(JsonInclude.Include.NON_NULL)
class DecisionCapabilities private constructor(
    questionKinds: Set<QuestionKind>,
    executionModes: Set<ExecutionMode>,
    @get:JsonProperty("maxQuestions") val maxQuestions: Int?,
    @get:JsonProperty("maxInputCharacters") val maxInputCharacters: Int?,
) {

    /**
     * The question kinds the service accepts. The set cannot be modified, holds at least one kind
     * and iterates in declaration order.
     */
    @get:JsonProperty("questionKinds")
    val questionKinds: Set<QuestionKind> = run {
        // EnumSet.copyOf cannot copy an empty plain set, so the emptiness check has to come first.
        require(questionKinds.isNotEmpty()) { "At least one question kind must be supported" }
        Collections.unmodifiableSet(EnumSet.copyOf(questionKinds))
    }

    /**
     * The execution modes the service supports. The set cannot be modified, holds at least one
     * mode and iterates in declaration order.
     */
    @get:JsonProperty("executionModes")
    val executionModes: Set<ExecutionMode> = run {
        require(executionModes.isNotEmpty()) { "At least one execution mode must be supported" }
        Collections.unmodifiableSet(EnumSet.copyOf(executionModes))
    }

    init {
        require(maxQuestions == null || maxQuestions >= 1) { "maxQuestions must be at least 1 when present" }
        require(maxInputCharacters == null || maxInputCharacters >= 1) { "maxInputCharacters must be at least 1 when present" }
    }

    /**
     * Returns a copy of these capabilities with the given question limit. Everything else stays
     * the same.
     *
     * @param maxQuestions the largest number of questions a request may hold, at least 1
     * @return new capabilities with the limit set
     * @throws IllegalArgumentException if the limit is below 1
     */
    fun withMaxQuestions(maxQuestions: Int): DecisionCapabilities =
        DecisionCapabilities(questionKinds, executionModes, maxQuestions, maxInputCharacters)

    /**
     * Returns a copy of these capabilities with the given input length limit. Everything else
     * stays the same.
     *
     * @param maxInputCharacters the largest input length in characters, at least 1
     * @return new capabilities with the limit set
     * @throws IllegalArgumentException if the limit is below 1
     */
    fun withMaxInputCharacters(maxInputCharacters: Int): DecisionCapabilities =
        DecisionCapabilities(questionKinds, executionModes, maxQuestions, maxInputCharacters)

    override fun equals(other: Any?): Boolean =
        this === other || other is DecisionCapabilities &&
            questionKinds == other.questionKinds &&
            executionModes == other.executionModes &&
            maxQuestions == other.maxQuestions &&
            maxInputCharacters == other.maxInputCharacters

    override fun hashCode(): Int =
        Objects.hash(questionKinds, executionModes, maxQuestions, maxInputCharacters)

    override fun toString(): String =
        "DecisionCapabilities(questionKinds=$questionKinds, executionModes=$executionModes, " +
            "maxQuestions=$maxQuestions, maxInputCharacters=$maxInputCharacters)"

    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionCapabilities", name, value)

    /**
     * Creates decision capabilities.
     */
    companion object {

        /**
         * Returns capabilities with the given question kinds and execution modes and no reported
         * limits.
         *
         * @param questionKinds the question kinds the service accepts, which must not be empty
         * @param executionModes the execution modes the service supports, which must not be empty
         * @return capabilities with both limits null
         * @throws IllegalArgumentException if either set is empty
         */
        @JvmStatic
        fun of(questionKinds: Set<QuestionKind>, executionModes: Set<ExecutionMode>): DecisionCapabilities =
            DecisionCapabilities(questionKinds, executionModes, null, null)

        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("questionKinds", required = true) questionKinds: Set<QuestionKind>,
            @JsonProperty("executionModes", required = true) executionModes: Set<ExecutionMode>,
            @JsonProperty("maxQuestions") maxQuestions: Int?,
            @JsonProperty("maxInputCharacters") maxInputCharacters: Int?,
        ): DecisionCapabilities = DecisionCapabilities(questionKinds, executionModes, maxQuestions, maxInputCharacters)
    }
}
