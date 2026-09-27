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

import org.jetbrains.annotations.ApiStatus
import java.util.Objects

/**
 * What a decision service can accept and how it can run a request. This is information a service
 * reports about itself; it does not run anything on its own.
 *
 * A null limit means the service does not report that limit, not that there is none.
 *
 * @property maxQuestions the largest number of questions a request may hold, or null when the
 * service does not report a limit
 * @property maxInputCharacters the largest input length in characters, or null when the service
 * does not report a limit
 */
@ApiStatus.Experimental
class DecisionCapabilities @JvmOverloads constructor(
    questionKinds: Set<QuestionKind>,
    executionModes: Set<ExecutionMode>,
    val maxQuestions: Int? = null,
    val maxInputCharacters: Int? = null,
) {

    /** The question kinds the service accepts. The set cannot be modified and holds at least one kind. */
    val questionKinds: Set<QuestionKind> = java.util.Set.copyOf(questionKinds)

    /** The execution modes the service supports. The set cannot be modified and holds at least one mode. */
    val executionModes: Set<ExecutionMode> = java.util.Set.copyOf(executionModes)

    init {
        require(this.questionKinds.isNotEmpty()) { "At least one question kind must be supported" }
        require(this.executionModes.isNotEmpty()) { "At least one execution mode must be supported" }
        require(maxQuestions == null || maxQuestions >= 1) { "maxQuestions must be at least 1 when present" }
        require(maxInputCharacters == null || maxInputCharacters >= 1) { "maxInputCharacters must be at least 1 when present" }
    }

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
}
