// A positive fixture for CompileNegativeTest: this must always compile. It mirrors the Java
// triage example from DecisionSpec's class doc, built with the customizer builder.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.decision.DecisionSpec;

public class Triage {
    static DecisionSpec spec() {
        return DecisionSpec.builder()
            .proposition("is_urgent", question -> question
                .asking("Does this convey urgency?"))
            .choice("department", question -> question
                .asking("Which team should handle this?")
                .option("billing", "Payments, invoicing, refunds")
                .option("technical", "Bugs, outages, integrations"))
            .rating("frustration", question -> question
                .asking("How frustrated is the customer?")
                .level("Calm")
                .level("Frustrated")
                .level("Very angry"))
            .build();
    }
}
