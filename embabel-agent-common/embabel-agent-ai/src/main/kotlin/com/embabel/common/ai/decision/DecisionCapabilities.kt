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
 * What a decision service can accept, as the service reports it.
 *
 * Start from [of] with the supported question kinds, then add any known limits with
 * [withMaxQuestions] and [withMaxInputCharacters]. Each call returns new capabilities.
 *
 * A null limit means the service does not report that limit. The service may still have one.
 *
 * The kinds always iterate in the order [QuestionKind] declares them, so `toString` and any
 * serialized form come out the same on every run.
 *
 * @property maxQuestions the largest number of questions a request may hold, or null when the
 * service does not report a limit
 * @property maxInputCharacters the largest input length in characters, or null when the service
 * does not report a limit
 */
@ApiStatus.Experimental
@JsonPropertyOrder("questionKinds", "maxQuestions", "maxInputCharacters")
@JsonInclude(JsonInclude.Include.NON_NULL)
class DecisionCapabilities private constructor(
    questionKinds: Set<QuestionKind>,
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
        DecisionCapabilities(questionKinds, maxQuestions, maxInputCharacters)

    /**
     * Returns a copy of these capabilities with the given input length limit. Everything else
     * stays the same.
     *
     * @param maxInputCharacters the largest input length in characters, at least 1
     * @return new capabilities with the limit set
     * @throws IllegalArgumentException if the limit is below 1
     */
    fun withMaxInputCharacters(maxInputCharacters: Int): DecisionCapabilities =
        DecisionCapabilities(questionKinds, maxQuestions, maxInputCharacters)

    override fun equals(other: Any?): Boolean =
        this === other || other is DecisionCapabilities &&
            questionKinds == other.questionKinds &&
            maxQuestions == other.maxQuestions &&
            maxInputCharacters == other.maxInputCharacters

    override fun hashCode(): Int =
        Objects.hash(questionKinds, maxQuestions, maxInputCharacters)

    override fun toString(): String =
        "DecisionCapabilities(questionKinds=$questionKinds, " +
            "maxQuestions=$maxQuestions, maxInputCharacters=$maxInputCharacters)"

    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionCapabilities", name, value)

    /**
     * Creates decision capabilities.
     */
    companion object {

        /**
         * Returns capabilities with the given question kinds and no reported limits.
         *
         * @param questionKinds the question kinds the service accepts, which must not be empty
         * @return capabilities with both limits null
         * @throws IllegalArgumentException if the set is empty
         */
        @JvmStatic
        fun of(questionKinds: Set<QuestionKind>): DecisionCapabilities =
            DecisionCapabilities(questionKinds, null, null)

        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("questionKinds", required = true) questionKinds: Set<QuestionKind>,
            @JsonProperty("maxQuestions") maxQuestions: Int?,
            @JsonProperty("maxInputCharacters") maxInputCharacters: Int?,
        ): DecisionCapabilities = DecisionCapabilities(questionKinds, maxQuestions, maxInputCharacters)
    }
}
