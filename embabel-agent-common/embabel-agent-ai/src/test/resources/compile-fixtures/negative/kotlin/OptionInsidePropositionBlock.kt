// A negative fixture for CompileNegativeTest: option(...) is a member of a choice builder, and a
// proposition block's receiver has no such member, so this must not compile.
package com.embabel.common.ai.decision.fixtures

import com.embabel.common.ai.decision.decisionSpec

fun broken2() = decisionSpec {
    proposition("is_urgent") {
        asking("Does this convey urgency?")
        option("no", "not allowed")
    }
}
