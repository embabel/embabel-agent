// A positive fixture for CompileNegativeTest: a decision service written against the base API
// only. Its compiled class declares none of the ask, capabilities or hook methods, so calls to
// them run the interface defaults.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.model.DecisionService;

public class LegacyJavaDecisionService implements DecisionService {

    private static final ModelProvenance PROVENANCE = new ModelProvenance("legacy-model", "legacy");

    @Override
    public String getName() {
        return "legacy-java-fixture";
    }

    @Override
    public String getProvider() {
        return "legacy";
    }

    @Override
    public ClassificationResult classify(ClassificationRequest request) {
        return new ClassificationResult.NoMatch(PROVENANCE);
    }

    @Override
    public PropositionResult assess(PropositionRequest request) {
        return new PropositionResult.Answered(true, PROVENANCE);
    }
}
