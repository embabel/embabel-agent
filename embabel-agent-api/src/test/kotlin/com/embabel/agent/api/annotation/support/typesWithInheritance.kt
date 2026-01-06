/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Condition
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.core.last

interface GetFromBlackboardInterfaceSuperAction {

    @Action(post = ["done"])
    fun frog(): Frog {
        return Frog("Kermit")
    }
}

@Agent(description = "thing")
class GetsFromBlackboardInheritedInterfaceAction : GetFromBlackboardInterfaceSuperAction {

    @Condition
    fun done(context: OperationContext): Boolean {
        return context.last(Frog::class.java) != null
    }

    @AchievesGoal(description = "Creating a prince from a frog")
    @Action(pre = ["done"])
    fun toPerson(
        context: OperationContext,
    ): PersonWithReverseTool {
        // Would be better to declare a Frog parameter but that's not what we are testing
        val frog = context.last<Frog>()!!
        return PersonWithReverseTool(frog.name)
    }

}

interface GetFromBlackboardInterfaceSuperActionOverride {

    @Action(post = ["done"])
    fun frog(): Frog
}

@Agent(description = "thing")
class GetsFromBlackboardInheritedInterfaceActionOverride : GetFromBlackboardInterfaceSuperActionOverride {

    override fun frog(): Frog {
        return Frog("Kermit")
    }

    @Condition
    fun done(context: OperationContext): Boolean {
        return context.last(Frog::class.java) != null
    }

    @AchievesGoal(description = "Creating a prince from a frog")
    @Action(pre = ["done"])
    fun toPerson(
        context: OperationContext,
    ): PersonWithReverseTool {
        // Would be better to declare a Frog parameter but that's not what we are testing
        val frog = context.last<Frog>()!!
        return PersonWithReverseTool(frog.name)
    }

}

abstract class GetFromBlackboardClassSuperAction {

    @Action(post = ["done"])
    fun frog(): Frog {
        return Frog("Kermit")
    }
}

@Agent(description = "thing")
class GetsFromBlackboardInheritedClassAction : GetFromBlackboardClassSuperAction() {

    @Condition
    fun done(context: OperationContext): Boolean {
        return context.last(Frog::class.java) != null
    }

    @AchievesGoal(description = "Creating a prince from a frog")
    @Action(pre = ["done"])
    fun toPerson(
        context: OperationContext,
    ): PersonWithReverseTool {
        // Would be better to declare a Frog parameter but that's not what we are testing
        val frog = context.last<Frog>()!!
        return PersonWithReverseTool(frog.name)
    }

}

abstract class GetFromBlackboardClassSuperActionOverride {

    @Action(post = ["done"])
    abstract fun frog(): Frog
}

@Agent(description = "thing")
class GetsFromBlackboardInheritedClassActionOverride : GetFromBlackboardClassSuperActionOverride() {

    override fun frog(): Frog {
        return Frog("Kermit")
    }

    @Condition
    fun done(context: OperationContext): Boolean {
        return context.last(Frog::class.java) != null
    }

    @AchievesGoal(description = "Creating a prince from a frog")
    @Action(pre = ["done"])
    fun toPerson(
        context: OperationContext,
    ): PersonWithReverseTool {
        // Would be better to declare a Frog parameter but that's not what we are testing
        val frog = context.last<Frog>()!!
        return PersonWithReverseTool(frog.name)
    }

}

interface GetGoalFromBlackboardInterfaceSuperAction {

    @Action(post = ["done"])
    fun frog(): Frog {
        return Frog("Kermit")
    }

    @AchievesGoal(description = "Creating a prince from a frog")
    @Action(pre = ["done"])
    fun toPerson(
        context: OperationContext,
    ): PersonWithReverseTool {
        // Would be better to declare a Frog parameter but that's not what we are testing
        val frog = context.last<Frog>()!!
        return PersonWithReverseTool(frog.name)
    }
}

@Agent(description = "thing")
class GetsGoalFromBlackboardInheritedInterfaceAction : GetGoalFromBlackboardInterfaceSuperAction {

    @Condition
    fun done(context: OperationContext): Boolean {
        return context.last(Frog::class.java) != null
    }

}

abstract class GetGoalFromBlackboardClassSuperAction {

    @Action(post = ["done"])
    fun frog(): Frog {
        return Frog("Kermit")
    }

    @AchievesGoal(description = "Creating a prince from a frog")
    @Action(pre = ["done"])
    fun toPerson(
        context: OperationContext,
    ): PersonWithReverseTool {
        // Would be better to declare a Frog parameter but that's not what we are testing
        val frog = context.last<Frog>()!!
        return PersonWithReverseTool(frog.name)
    }
}

@Agent(description = "thing")
class GetsGoalFromBlackboardInheritedClassAction : GetGoalFromBlackboardClassSuperAction() {

    @Condition
    fun done(context: OperationContext): Boolean {
        return context.last(Frog::class.java) != null
    }

}


interface GetConditionFromBlackboardInterfaceSuperAction {

    @Condition
    fun done(context: OperationContext): Boolean {
        return context.last(Frog::class.java) != null
    }

}

@Agent(description = "thing")
class GetsConditionFromBlackboardInheritedInterfaceAction : GetConditionFromBlackboardInterfaceSuperAction {

    @Action(post = ["done"])
    fun frog(): Frog {
        return Frog("Kermit")
    }

    @AchievesGoal(description = "Creating a prince from a frog")
    @Action(pre = ["done"])
    fun toPerson(
        context: OperationContext,
    ): PersonWithReverseTool {
        // Would be better to declare a Frog parameter but that's not what we are testing
        val frog = context.last<Frog>()!!
        return PersonWithReverseTool(frog.name)
    }

}

abstract class GetConditionFromBlackboardClassSuperAction {

    @Condition
    fun done(context: OperationContext): Boolean {
        return context.last(Frog::class.java) != null
    }

}

@Agent(description = "thing")
class GetsConditionFromBlackboardInheritedClassAction : GetConditionFromBlackboardClassSuperAction() {

    @Action(post = ["done"])
    fun frog(): Frog {
        return Frog("Kermit")
    }

    @AchievesGoal(description = "Creating a prince from a frog")
    @Action(pre = ["done"])
    fun toPerson(
        context: OperationContext,
    ): PersonWithReverseTool {
        // Would be better to declare a Frog parameter but that's not what we are testing
        val frog = context.last<Frog>()!!
        return PersonWithReverseTool(frog.name)
    }

}
