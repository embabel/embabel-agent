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
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
    property = "fqn"
)
sealed interface GenericIntent<T> {
    val thing: T
}

class BillingIntent2(
    override val thing: String = "billing",
) : GenericIntent<String>

class SalesIntent2(
    override val thing: Long = 42L,
) : GenericIntent<Long>

class ServiceIntent2(
    override val thing: Double = 3.14,
) : GenericIntent<Double>


@Agent(
    description = "Figure out the department a customer wants to transfer to",
)
class GenericIntentReceptionAgent {

    @Action
    fun classifyIntent(userInput: UserInput): GenericIntent<*>? =
        when (userInput.content) {
            "billing" -> BillingIntent2("")
            "sales" -> SalesIntent2(2)
            "service" -> ServiceIntent2()
            else -> {
                loggerFor<IntentReceptionAgent>().warn("Unknown intent: $userInput")
                null
            }
        }

    @Action
    fun billingAction(intent: BillingIntent2): IntentClassificationSuccess {
        return IntentClassificationSuccess("billing")
    }

    @Action
    fun salesAction(intent: SalesIntent2): IntentClassificationSuccess {
        return IntentClassificationSuccess("sales")
    }

    @Action
    fun serviceAction(intent: ServiceIntent2): IntentClassificationSuccess {
        return IntentClassificationSuccess("service")
    }

    @AchievesGoal(description = "The department has been determined")
    @Action
    fun success(success: IntentClassificationSuccess): IntentClassificationSuccess {
        return success
    }
}
