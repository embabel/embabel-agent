/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.common.autonomy

import com.embabel.agent.core.Goal
import com.embabel.agent.spi.Rankings
import com.embabel.common.core.types.ZeroToOne

data class GoalChoiceApprovalRequest(
    val goal: Goal,
    val intent: String,
    val rankings: Rankings<Goal>,
)

sealed interface GoalChoiceApprovalResponse {
    val request: GoalChoiceApprovalRequest
    val approved: Boolean
}

data class GoalChoiceApproved(
    override val request: GoalChoiceApprovalRequest,
) : GoalChoiceApprovalResponse {
    override val approved: Boolean = true
}

data class GoalChoiceNotApproved(
    override val request: GoalChoiceApprovalRequest,
    val reason: String,
) : GoalChoiceApprovalResponse {
    override val approved: Boolean = false
}

/**
 * Implemented by objects that can veto goal choice
 */
fun interface GoalChoiceApprover {

    /**
     * Respond to a goal choice.
     */
    fun approve(
        goalChoiceApprovalRequest: GoalChoiceApprovalRequest,
    ): GoalChoiceApprovalResponse

    companion object {
        val APPROVE_ALL = GoalChoiceApprover { GoalChoiceApproved(it) }

        /**
         * Approve if the score is greater than this value
         */
        infix fun approveWithScoreOver(score: ZeroToOne) = GoalChoiceApprover { request ->
            if ((request.rankings.rankings().firstOrNull()?.score ?: 0.0) > score) {
                GoalChoiceApproved(request)
            } else {
                GoalChoiceNotApproved(
                    request = request,
                    reason = "Score ${request.rankings.rankings().firstOrNull()?.score} is not over $score",
                )
            }
        }
    }

}
