// A negative fixture for CompileNegativeTest: ServiceSelector is invariant in its service type, so
// a classification selector cannot be assigned to a decision selector.
package com.embabel.common.ai.decision.fixtures;

import com.embabel.common.ai.classification.ClassificationService;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.model.ServiceSelector;

public class ClassificationSelectorAsDecision {
    static ServiceSelector<DecisionService> wrong(ServiceSelector<ClassificationService> classifications) {
        ServiceSelector<DecisionService> decisions = classifications;
        return decisions;
    }
}
