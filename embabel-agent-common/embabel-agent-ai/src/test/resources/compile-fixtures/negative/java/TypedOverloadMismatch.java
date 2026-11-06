// A negative fixture for CompileNegativeTest: the response builder's answer(...) overloads pair
// each question type with its own result type, so a proposition question with a rating result
// must not compile.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;

public class TypedOverloadMismatch {
    static DecisionResponse.Builder wrong(
            DecisionSpec spec, PropositionQuestionSpec urgentProposition, RatingResult ratingResult) {
        return DecisionResponse.builder(spec).answer(urgentProposition, ratingResult);
    }
}
