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
package com.embabel.agent.api.annotation.support.supervisor

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.PlannerType

/**
 * Domain objects for the 3-step baking workflow.
 */
data class Ingredient(val name: String)

data class Dough(val ingredient: String)

data class Bread(val dough: String)

data class Meal(val bread: String, val description: String)

/**
 * A supervisor agent with 3 steps to demonstrate multi-step tool progression.
 *
 * Workflow: Ingredient -> Dough -> Bread -> Meal (goal)
 *
 * This demonstrates how the supervisor should:
 * 1. See makeDough as ready when Ingredient is on blackboard
 * 2. After makeDough runs, see bakeBread as ready (Dough is now available)
 * 3. After bakeBread runs, see serveMeal as the goal (Bread is available)
 */
@Agent(
    planner = PlannerType.SUPERVISOR,
    description = "Baking supervisor: takes ingredients and produces a meal through multiple steps",
)
class SupervisorWith3Steps {

    @Action(description = "Mix the ingredient into dough")
    fun makeDough(ingredient: Ingredient): Dough {
        return Dough(ingredient = ingredient.name)
    }

    @Action(description = "Bake the dough into bread")
    fun bakeBread(dough: Dough): Bread {
        return Bread(dough = dough.ingredient)
    }

    @AchievesGoal(description = "Serve the bread as a meal")
    @Action(description = "Serve the bread as a delicious meal")
    fun serveMeal(bread: Bread): Meal {
        return Meal(
            bread = bread.dough,
            description = "A delicious meal made from ${bread.dough}"
        )
    }
}
