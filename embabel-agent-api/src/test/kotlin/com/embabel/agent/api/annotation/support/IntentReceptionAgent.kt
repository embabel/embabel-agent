package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.domain.io.UserInput

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
    fun classifyIntent(userInput: UserInput): Intent =
        when (userInput.content) {
            "billing" -> BillingIntent()
            "sales" -> SalesIntent()
            "service" -> ServiceIntent()
            else -> throw IllegalArgumentException("Unknown desire: $userInput")
        }

    @Action()
    fun billingAction(intent: BillingIntent): IntentClassificationSuccess {
        println("This is the billing intent: $intent")
        return IntentClassificationSuccess("billing")
    }

    @Action()
    fun salesAction(intent: SalesIntent): IntentClassificationSuccess {
        println("This is the sales intent: $intent")
        return IntentClassificationSuccess("sales")
    }

    @Action()
    fun serviceAction(intent: ServiceIntent): IntentClassificationSuccess {
        println("This is the service intent: $intent")
        return IntentClassificationSuccess("service")
    }

    @AchievesGoal(description = "The department has been determined")
    @Action()
    fun success(success: IntentClassificationSuccess): IntentClassificationSuccess {
        println(success)
        return IntentClassificationSuccess("We did it")
    }
}