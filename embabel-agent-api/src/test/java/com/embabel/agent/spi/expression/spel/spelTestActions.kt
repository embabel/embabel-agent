package com.embabel.agent.spi.expression.spel

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.PersonWithReverseTool
import com.embabel.agent.api.common.PlannerType


data class Elephant(
    val name: String,
    val age: Int,
)

data class Zoo(
    val elephant: Elephant,
)

@Agent(
    description = "thing",
    planner = PlannerType.UTILITY,
    version = "4.0.0"
)
class Spel2ActionsNoGoal {

    @Action
    fun makeElephant(): Elephant {
        return Elephant("Zaboya", 30)
    }

    @Action(
        pre = ["spel:elephant.age > 20"]
    )
    fun makeZoo(elephant: Elephant): Zoo {
        return Zoo(elephant)
    }

}

@Agent(
    description = "thing",
    planner = PlannerType.SUPERVISOR,
    version = "4.0.0"
)
class Spel2ActionsSuperVisor {

    @Action
    fun makeElephant(): Elephant {
        return Elephant("Zaboya", 30)
    }

    @Action(
        pre = ["spel:elephant.age > 20"]
    )
    @AchievesGoal(description = "All stages complete")
    fun makeZoo(elephant: Elephant): Zoo {
        return Zoo(elephant)
    }
}

@Agent(
    description = "thing with young elephant",
    planner = PlannerType.UTILITY
)
class Spel2ActionsYoungElephant {

    @Action
    fun makeYoungElephant(): Elephant {
        return Elephant("Dumbo", 15)
    }

    @Action(
        pre = ["spel:elephant.age > 20"]
    )
    fun makeZoo(elephant: Elephant): Zoo {
        return Zoo(elephant)
    }

}

@Agent(
    description = "thing",
    planner = PlannerType.UTILITY
)
class Spel2ActionsMultiParameterNoGoal {

    @Action
    fun makeElephant(): Elephant {
        return Elephant("Zaboya", 30)
    }

    @Action
    fun canRun(): PersonWithReverseTool {
        return PersonWithReverseTool("Runner")
    }

    @Action(
        pre = ["spel:elephant.age > 20 && personWithReverseTool.name == 'Runner'"]
    )
    fun makeZoo(
        elephant: Elephant,
        person: PersonWithReverseTool,
    ): Zoo {
        return Zoo(elephant)
    }

}