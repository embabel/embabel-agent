// A negative fixture for CompileNegativeTest: option(...) does not exist on a rating builder,
// only on a choice builder, so this must not compile.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.decision.DecisionSpec;

public class OptionOnRatingBuilder {
    static DecisionSpec spec() {
        return DecisionSpec.builder()
            .rating("frustration", question -> question
                .asking("How frustrated is the customer?")
                .option("nope", "not allowed"))
            .build();
    }
}
