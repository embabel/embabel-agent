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
 * How a decision spec can be run against a model.
 */
@ApiStatus.Experimental
enum class ExecutionMode {

    /**
     * The whole spec is evaluated in one provider operation, so every answer shares one model
     * evaluation.
     */
    @JsonProperty("native")
    NATIVE,

    /**
     * A spec holding one proposition or choice question is answered through the matching existing
     * service method. A single rating question has no such method, so this mode does not cover it.
     */
    @JsonProperty("single_question")
    SINGLE_QUESTION,

    /**
     * Each question is asked in its own call, so the answers are not one shared evaluation.
     */
    @JsonProperty("sequential")
    SEQUENTIAL,
}

/**
 * The execution modes a caller allows when a decision spec is run. A service that supports more
 * than one of the allowed modes chooses which one to use.
 *
 * The modes always iterate in the order [ExecutionMode] declares them, so `toString` and any
 * serialized form come out the same on every run.
 */
@ApiStatus.Experimental
class DecisionOptions private constructor(executionModes: Set<ExecutionMode>) {

    /**
     * The modes this caller allows. The set cannot be modified, holds at least one mode and
     * iterates in declaration order.
     */
    @get:JsonProperty("executionModes")
    val executionModes: Set<ExecutionMode> = run {
        // EnumSet.copyOf cannot copy an empty plain set, so the emptiness check has to come first.
        require(executionModes.isNotEmpty()) { "At least one execution mode must be allowed" }
        Collections.unmodifiableSet(EnumSet.copyOf(executionModes))
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is DecisionOptions && executionModes == other.executionModes

    override fun hashCode(): Int = executionModes.hashCode()

    override fun toString(): String = "DecisionOptions(executionModes=$executionModes)"

    @JsonAnySetter
    private fun unknownMember(name: String, value: Any?): Nothing = rejectUnknownMember("DecisionOptions", name, value)

    /**
     * Creates decision options.
     */
    companion object {

        /**
         * The default options, allowing [ExecutionMode.NATIVE] and [ExecutionMode.SINGLE_QUESTION].
         * Decomposition into separate sequential calls is not allowed by default.
         *
         * @return the default options
         */
        @JvmStatic
        fun defaults(): DecisionOptions = DecisionOptions(setOf(ExecutionMode.NATIVE, ExecutionMode.SINGLE_QUESTION))

        /**
         * Options that allow only [ExecutionMode.NATIVE], so every request must be answered in one
         * shared evaluation.
         *
         * @return options allowing only native execution
         */
        @JvmStatic
        fun nativeOnly(): DecisionOptions = DecisionOptions(setOf(ExecutionMode.NATIVE))

        /**
         * Options that allow all three execution modes, including [ExecutionMode.SEQUENTIAL].
         *
         * @return options allowing every execution mode
         */
        @JvmStatic
        fun allowingSequential(): DecisionOptions = DecisionOptions(ExecutionMode.entries.toSet())

        /**
         * Returns options that allow exactly the given modes.
         *
         * @param executionModes the modes to allow, which must not be empty
         * @return options allowing the given modes
         * @throws IllegalArgumentException if the set is empty
         */
        @JvmStatic
        fun of(executionModes: Set<ExecutionMode>): DecisionOptions = DecisionOptions(executionModes)

        /**
         * Returns options that allow exactly the given modes. A mode given more than once is
         * allowed once.
         *
         * @param first a mode to allow
         * @param rest any further modes to allow
         * @return options allowing the given modes
         */
        @JvmStatic
        fun of(first: ExecutionMode, vararg rest: ExecutionMode): DecisionOptions =
            DecisionOptions(EnumSet.of(first, *rest))

        // A mode listed twice in JSON reads as one.
        @JvmStatic
        @JsonCreator
        private fun fromJson(
            @JsonProperty("executionModes", required = true) executionModes: Set<ExecutionMode>,
        ): DecisionOptions = DecisionOptions(executionModes)
    }
}
