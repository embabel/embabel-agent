// A negative fixture for CompileNegativeTest: the builder's internal companion factory compiles to
// a synthetic method with a mangled name, so even calling it by that JVM name must not compile.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.decision.PropositionQuestionSpec;

public class MangledFactoryCall {
    static PropositionQuestionSpec.Builder make() {
        return PropositionQuestionSpec.Builder.Companion.create$embabel_agent_ai("is_urgent");
    }
}
