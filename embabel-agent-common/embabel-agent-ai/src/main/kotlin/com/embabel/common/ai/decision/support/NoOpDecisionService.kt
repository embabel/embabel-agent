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
package com.embabel.common.ai.decision.support

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.spi.ChoiceAssessment
import com.embabel.common.ai.decision.spi.NativeQuestionSetExecution
import com.embabel.common.ai.decision.spi.RatingAssessment
import com.embabel.common.ai.model.DecisionService
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import java.util.EnumSet

/**
 * A decision service for disabled configuration. Every operation returns an
 * [FailureReason.UNAVAILABLE] failure and makes no provider call.
 *
 * It accepts every question kind and answers a whole request in one native call, so a request
 * reaches it and comes back as a request failure. A failure is never a judgment about the input, so callers can
 * tell a disabled service from a false, no-match or inconclusive answer. Construction logs one
 * INFO line, and each failed request logs the usual WARN line.
 *
 * @param name the service name, `none` by default
 */
@ApiStatus.Experimental
class NoOpDecisionService @JvmOverloads constructor(
    override val name: String = "none",
) : DecisionService, NativeQuestionSetExecution, ChoiceAssessment, RatingAssessment {

    override val provider: String get() = PROVIDER

    init {
        logger.info("Decision service '{}' is disabled; every request returns an unavailable failure", name)
    }

    override fun capabilities(): DecisionCapabilities = CAPABILITIES

    override fun classify(request: ClassificationRequest): ClassificationResult {
        logger.debug("Decision service '{}' is disabled: classify returns UNAVAILABLE", name)
        return ClassificationResult.Failure(FailureReason.UNAVAILABLE)
    }

    override fun assess(request: PropositionRequest): PropositionResult {
        logger.debug("Decision service '{}' is disabled: assess returns UNAVAILABLE", name)
        return PropositionResult.Failure(FailureReason.UNAVAILABLE)
    }

    override fun choose(input: String, question: ChoiceQuestionSpec): ClassificationResult {
        logger.debug("Decision service '{}' is disabled: choose returns UNAVAILABLE", name)
        return ClassificationResult.Failure(FailureReason.UNAVAILABLE)
    }

    override fun rate(input: String, question: RatingQuestionSpec): RatingResult {
        logger.debug("Decision service '{}' is disabled: rate returns UNAVAILABLE", name)
        return RatingResult.Failure(FailureReason.UNAVAILABLE)
    }

    override fun askNative(request: DecisionRequest): DecisionResponse {
        logger.debug("Decision service '{}' is disabled: askNative returns UNAVAILABLE", name)
        return DecisionResponse.failed(request.spec, FailureReason.UNAVAILABLE)
    }

    override fun toString(): String = "NoOpDecisionService(name=$name)"

    private companion object {
        const val PROVIDER = "none"

        val CAPABILITIES: DecisionCapabilities = DecisionCapabilities.of(EnumSet.allOf(QuestionKind::class.java))

        val logger = LoggerFactory.getLogger(NoOpDecisionService::class.java)
    }
}
