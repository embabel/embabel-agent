// A negative fixture for CompileNegativeTest: the builder's internal companion factory is
// hidden from Java, so calling it by its Kotlin name must not compile.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.decision.PropositionQuestionSpec;

public class MangledFactoryCall {
    static PropositionQuestionSpec.Builder make() {
        return PropositionQuestionSpec.Builder.create("is_urgent");
    }
}
