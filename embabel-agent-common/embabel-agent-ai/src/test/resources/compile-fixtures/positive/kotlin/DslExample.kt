// A positive fixture for CompileNegativeTest: this must always compile. It is the same DSL
// example carried in DecisionSpecDslTest's tag::dsl[] block.
package com.embabel.common.ai.decision.fixtures

import com.embabel.common.ai.decision.decisionSpec

fun triage() = decisionSpec {
    proposition("is_urgent") {
        asking("Does this convey urgency?")
    }
    choice("department") {
        asking("Which team should handle this?")
        option("billing", "Payments, invoicing, refunds")
        option("technical", "Bugs, outages, integrations")
    }
    rating("frustration") {
        asking("How frustrated is the customer?")
        level("Calm")
        level("Frustrated")
        level("Very angry")
    }
}
