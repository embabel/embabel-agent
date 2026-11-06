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
import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.EnumSet

/**
 * What a decision service can accept, as the service reports it: the question kinds it answers.
 * Create one with [of].
 *
 * The kinds always iterate in the order [QuestionKind] declares them, so `toString` and any
 * serialized form come out the same on every run.
 */
@ApiStatus.Experimental
class DecisionCapabilities private constructor(questionKinds: Set<QuestionKind>) {

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

    override fun equals(other: Any?): Boolean =
        this === other || other is DecisionCapabilities && questionKinds == other.questionKinds

    override fun hashCode(): Int = questionKinds.hashCode()

    override fun toString(): String = "DecisionCapabilities(questionKinds=$questionKinds)"

    /**
     * Rejects a JSON member this type doesn't define.
     *
     * @param name the unknown member's name
     * @param value the unknown member's value
     */
    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionCapabilities", name, value)

    /**
     * Creates decision capabilities.
     */
    companion object {

        /**
         * Returns capabilities with the given question kinds.
         *
         * @param questionKinds the question kinds the service accepts, which must not be empty
         * @return the capabilities
         * @throws IllegalArgumentException if the set is empty
         */
        @JvmStatic
        fun of(questionKinds: Set<QuestionKind>): DecisionCapabilities = DecisionCapabilities(questionKinds)

        /**
         * Builds capabilities from deserialized JSON fields.
         *
         * @param questionKinds the question kinds the service accepts
         * @return the capabilities
         */
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        private fun fromJson(
            @JsonProperty("questionKinds", required = true) questionKinds: Set<QuestionKind>,
        ): DecisionCapabilities = DecisionCapabilities(questionKinds)
    }
}
