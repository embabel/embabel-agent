// A negative fixture for CompileNegativeTest: option(...) does not exist on a proposition
// builder, only on a choice builder, so this must not compile.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.decision.DecisionSpec;

public class OptionOnPropositionBuilder {
    static DecisionSpec spec() {
        return DecisionSpec.builder()
            .proposition("is_urgent", question -> question
                .asking("Does this convey urgency?")
                .option("nope", "not allowed"))
            .build();
    }
}
