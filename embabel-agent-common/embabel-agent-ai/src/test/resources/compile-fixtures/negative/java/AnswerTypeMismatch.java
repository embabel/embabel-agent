// A negative fixture for CompileNegativeTest: a proposition question's outcome is a
// PropositionResult, so assigning response.answer(propositionQuestion) to a ClassificationResult
// must not compile.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;

public class AnswerTypeMismatch {
    static ClassificationResult wrong(PropositionQuestionSpec urgent, DecisionSpec spec) {
        DecisionResponse response = DecisionResponse.failed(spec, FailureReason.UNAVAILABLE);
        ClassificationResult mismatched = response.answer(urgent);
        return mismatched;
    }
}
