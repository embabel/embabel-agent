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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.domain.io.UserInput
import com.embabel.common.util.loggerFor

sealed class Intent
class BillingIntent : Intent()
class SalesIntent : Intent()
class ServiceIntent : Intent()

data class IntentClassificationSuccess(val text: String)

@Agent(
    description = "Figure out the department a customer wants to transfer to",
)
class IntentReceptionAgent() {

    @Action
    fun classifyIntent(userInput: UserInput): Intent? =
        when (userInput.content) {
            "billing" -> BillingIntent()
            "sales" -> SalesIntent()
            "service" -> ServiceIntent()
            else -> {
                loggerFor<IntentReceptionAgent>().warn("Unknown intent: $userInput")
                null
            }
        }

    @Action
    fun billingAction(intent: BillingIntent): IntentClassificationSuccess {
        return IntentClassificationSuccess("billing")
    }

    @Action
    fun salesAction(intent: SalesIntent): IntentClassificationSuccess {
        return IntentClassificationSuccess("sales")
    }

    @Action
    fun serviceAction(intent: ServiceIntent): IntentClassificationSuccess {
        return IntentClassificationSuccess("service")
    }

    @AchievesGoal(description = "The department has been determined")
    @Action
    fun success(success: IntentClassificationSuccess): IntentClassificationSuccess {
        return success
    }
}
