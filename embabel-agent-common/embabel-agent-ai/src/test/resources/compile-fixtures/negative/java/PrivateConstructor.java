// A negative fixture for CompileNegativeTest: PropositionQuestionSpec's constructor is private,
// so a question can only come from its builder, and this must not compile.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.decision.PropositionQuestionSpec;

public class PrivateConstructor {
    static PropositionQuestionSpec make() {
        return new PropositionQuestionSpec("is_urgent", "Does this convey urgency?");
    }
}
