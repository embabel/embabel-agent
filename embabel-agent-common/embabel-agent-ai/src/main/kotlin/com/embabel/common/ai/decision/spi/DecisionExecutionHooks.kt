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
package com.embabel.common.ai.decision.spi

import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import org.jetbrains.annotations.ApiStatus

/**
 * A decision service that forwards its hook calls to another service.
 *
 * Such a decorator implements every hook interface that [hookSource] implements. Preflight and
 * routing inspect the hooks of [hookSource], and execution calls those hooks on the decorator. A
 * request routed through a hook the decorator lacks fails preflight with an
 * [IllegalStateException] before any provider call.
 */
@ApiStatus.Experimental
interface DelegatingDecisionService {

    /** The innermost service whose hooks answer this decorator's calls. */
    val hookSource: DecisionService
}

// These interfaces declare no default methods. A class may implement several of them, and two
// unrelated defaults with the same signature would be a conflict for that class.

/**
 * A decision service that answers every question of a request in one provider operation.
 *
 * Execution sends the whole request to this method when the service implements it. The service's
 * capabilities list every question kind it answers this way. Execution reaches this method only
 * after the request has passed preflight.
 */
@ApiStatus.Experimental
fun interface NativeQuestionSetExecution {

    /**
     * Answers every question of the request in one provider operation.
     *
     * The response answers the request's spec. A provider failure for one question is that
     * question's typed failure outcome. A failure of the whole request is
     * `DecisionResponse.failed(spec, reason)`.
     *
     * @param request the input and the questions to answer
     * @return the response for the request's spec
     */
    fun askNative(request: DecisionRequest): DecisionResponse
}

/**
 * A decision service that answers one proposition question, with its name and instructions, in its
 * own provider call.
 *
 * Per-question execution answers proposition questions through this interface when the service
 * implements it, and through `assess` otherwise.
 */
@ApiStatus.Experimental
fun interface PropositionAssessment {

    /**
     * Answers one proposition question in its own provider call.
     *
     * @param input the text the model reasons over
     * @param question the proposition question, with its name and instructions
     * @return the question's outcome
     */
    fun assess(input: String, question: PropositionQuestionSpec): PropositionResult
}

/**
 * A decision service that rates the input against one rating question in its own provider call.
 *
 * Per-question execution answers rating questions only through this interface.
 */
@ApiStatus.Experimental
fun interface RatingAssessment {

    /**
     * Rates the input against one rating question in its own provider call. The caller validates
     * the outcome against the question's levels.
     *
     * @param input the text the model reasons over
     * @param question the rating question, with its instructions and levels
     * @return the question's outcome
     */
    fun rate(input: String, question: RatingQuestionSpec): RatingResult
}
