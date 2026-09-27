// A negative fixture for CompileNegativeTest: Question is sealed with only
// PropositionQuestionSpec, ChoiceQuestionSpec and RatingQuestionSpec permitted, so a class
// implementing it from outside must not compile.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.decision.Question;
import com.embabel.common.ai.decision.QuestionKind;

public class QuestionImplementation implements Question<String> {
    @Override
    public String getName() {
        return "custom";
    }

    @Override
    public String getInstructions() {
        return "custom instructions";
    }

    @Override
    public QuestionKind getKind() {
        return QuestionKind.PROPOSITION;
    }

    @Override
    public String getDefinitionId() {
        return "d1-custom";
    }
}
