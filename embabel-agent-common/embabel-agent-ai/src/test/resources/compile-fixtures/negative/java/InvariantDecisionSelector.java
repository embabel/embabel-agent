// A negative fixture for CompileNegativeTest: the decision selector takes decision services only,
// so passing a classification-only service to using(...) must not compile.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.model.ClassificationService;
import com.embabel.common.ai.model.DecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;

public class InvariantDecisionSelector {
    static DecisionService wrong(DecisionServiceRegistry registry, ClassificationService classificationOnly) {
        return registry.decisions().using(classificationOnly);
    }
}
