// A negative fixture for CompileNegativeTest: level(...) does not exist on a choice builder,
// only on a rating builder, so this must not compile.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.decision.DecisionSpec;

public class LevelOnChoiceBuilder {
    static DecisionSpec spec() {
        return DecisionSpec.builder()
            .choice("department", question -> question
                .asking("Which team should handle this?")
                .level("nope"))
            .build();
    }
}
