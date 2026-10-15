// A negative fixture for CompileNegativeTest: choice(...) belongs to the outer decisionSpec
// scope, and the DSL marker stops a proposition block from reaching it without a label, so this
// must not compile.
package com.embabel.common.ai.decision.fixtures

import com.embabel.common.ai.decision.decisionSpec

fun broken() = decisionSpec {
    proposition("is_urgent") {
        asking("Does this convey urgency?")
        choice("nested") {
            asking("Not allowed here")
        }
    }
}
