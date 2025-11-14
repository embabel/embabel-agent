/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.plan.goap

import com.embabel.common.util.time
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.*

object EmptyWorldStateDeterminer : WorldStateDeterminer {
    override fun determineWorldState(): GoapWorldState {
        return GoapWorldState()
    }

    override fun determineCondition(condition: String): ConditionDetermination =
        ConditionDetermination.UNKNOWN
}

class GoapPlannerTest {

    @Nested
    inner class Crime {

        val cookDrugs = GoapAction(
            name = "Cook drugs",
            preconditions = emptyMap(),
            effects = mapOf("hasDrugs" to ConditionDetermination(true), "legalPeril" to ConditionDetermination(true)),
            cost = { 1.20 },
        )

        val sellDrugs = GoapAction(
            name = "Sell drugs",
            preconditions = mapOf("hasDrugs" to ConditionDetermination(true)),
            effects = mapOf(
                "hasDrugs" to ConditionDetermination(false),
                "hasMoney" to ConditionDetermination(true),
                "legalPeril" to ConditionDetermination(true)
            ),
            cost = { 1.20 },
        )

        val buyGun = GoapAction(
            name = "Buy gun",
            preconditions = mapOf("hasMoney" to ConditionDetermination(true)),
            effects = mapOf("hasGun" to ConditionDetermination(true), "hasMoney" to ConditionDetermination(false)),
            cost = { 1.0 },
        )

        val bribeCop = GoapAction(
            name = "Bribe cop",
            preconditions = mapOf("hasMoney" to ConditionDetermination(true)),
            effects = mapOf("legalPeril" to ConditionDetermination(false), "hasMoney" to ConditionDetermination(false)),
            cost = { 2.0 },
        )

        val shootEnemy = GoapAction(
            name = "Shoot enemy",
            preconditions = mapOf("hasGun" to ConditionDetermination(true)),
            effects = mapOf("enemyDead" to ConditionDetermination(true), "legalPeril" to ConditionDetermination(true)),
            cost = { 1.0 },
        )

        val buyPoison = GoapAction(
            name = "Buy poison",
            preconditions = mapOf("hasMoney" to ConditionDetermination(true)),
            effects = mapOf("hasPoison" to ConditionDetermination(true), "hasMoney" to ConditionDetermination(false)),
            cost = { 3.0 },
        )

        val poisonEnemy = GoapAction(
            name = "Poison enemy",
            preconditions = mapOf("hasPoison" to ConditionDetermination(true)),
            effects = mapOf("enemyDead" to ConditionDetermination(true), "legalPeril" to ConditionDetermination(true)),
            cost = { 1.0 },
        )

        val getAwayWithMurderGoal =
            GoapGoal(
                name = "getAwayWithMurder",
                preconditions = mapOf(
                    "enemyDead" to ConditionDetermination(true),
                    "legalPeril" to ConditionDetermination(false)
                ),
                value = { 10.0 },
            )

        val actions = setOf(
            cookDrugs,
            buyGun,
            shootEnemy,
            bribeCop,
            sellDrugs,
            buyPoison,
            poisonEnemy,
        )

        val bestPlanActions =
            listOf("Cook drugs", "Sell drugs", "Buy gun", "Cook drugs", "Shoot enemy", "Sell drugs", "Bribe cop")


        @Test
        fun `single plan`() {
            val planner = AStarGoapPlanner(EmptyWorldStateDeterminer)
            val plan = planner.planToGoal(actions, getAwayWithMurderGoal)
            assertTrue(plan != null)
            assertEquals(
                bestPlanActions,
                plan!!.actions.map { it.name })
        }

        @Test
        fun `should find 2 plans`() {
            val planner = AStarGoapPlanner(EmptyWorldStateDeterminer)
            val hasGunGoal = GoapGoal("hasGun", value = { 1.0 })
            val goapSystem = GoapPlanningSystem(actions, setOf(getAwayWithMurderGoal, hasGunGoal))
            val plans = planner.plansToGoals(goapSystem)
            assertEquals(plans.size, 2)
            val best = plans.first()
            assertEquals(
                bestPlanActions,
                best.actions.map { it.name })
            assertTrue(best.netValue(mockk()) > 0.0)
            assertTrue(best.cost(mockk()) > 0.0)
        }

        @Test
        fun `best plan to any goal`() {
            val planner = AStarGoapPlanner(EmptyWorldStateDeterminer)
            val hasGunGoal = GoapGoal(name = "hasGun", value = { 1.0 })
            val goapSystem = GoapPlanningSystem(actions, setOf(getAwayWithMurderGoal, hasGunGoal))
            val plan = planner.bestValuePlanToAnyGoal(goapSystem)
            assertNotNull(plan)
            assertEquals(
                bestPlanActions,
                plan!!.actions.map { it.name })
        }

        @Test
        fun `find path from unknown`() {
            val touchyWorldStateDeterminer = object : WorldStateDeterminer {
                override fun determineWorldState(): GoapWorldState {
                    return GoapWorldState(
                        state = mapOf(
                            "hasMoney" to ConditionDetermination(true),
                            "enemyDead" to ConditionDetermination.UNKNOWN,
                        )
                    )
                }

                override fun determineCondition(condition: String): ConditionDetermination =
                    ConditionDetermination.UNKNOWN
            }
            val planner = AStarGoapPlanner(touchyWorldStateDeterminer)
            val plan = planner.planToGoal(actions, getAwayWithMurderGoal)
            assertTrue(
                plan!!.actions.map { it.name }.containsAll(bestPlanActions.drop(2))
            )
        }

        @Test
        fun `find path, force evaluating unknown`() {
            // We are going to have to ask for enemyDead to be evaluated
            // Will find that the goal has been achieved if we force evaluate that condition
            val forceEvaluated = mutableListOf<String>()
            val touchyWorldStateDeterminer = object : WorldStateDeterminer {

                override fun determineWorldState(): GoapWorldState {
                    return GoapWorldState(
                        state = mapOf(
                            "legalPeril" to ConditionDetermination.FALSE,
                            "enemyDead" to ConditionDetermination.UNKNOWN,
                        )
                    )
                }

                override fun determineCondition(condition: String): ConditionDetermination {
                    assertEquals(condition, "enemyDead")
                    forceEvaluated += condition
                    return ConditionDetermination.TRUE
                }
            }
            val planner = AStarGoapPlanner(touchyWorldStateDeterminer)
            val plan = planner.planToGoal(
                actions,
                getAwayWithMurderGoal
            )
            assertNotNull(plan)
            assertEquals(0, plan!!.actions.size)
            assertTrue(forceEvaluated.contains("enemyDead"), "Should have force evaluated enemy dead")
        }

        private fun generateRandomActions(num: Int): List<GoapAction> {
            val random = Random()
            return List(num) {
                GoapAction(
                    name = random.nextInt().toString(),
                    preconditions = mapOf(
                        "hasGun" to ConditionDetermination(true),
                        "hasPoison" to ConditionDetermination(false),
                        UUID.randomUUID().toString() to ConditionDetermination(true)
                    ),
                    effects = mapOf(
                        "hasPoison" to ConditionDetermination(true),
                        UUID.randomUUID().toString() to ConditionDetermination(true),
                        UUID.randomUUID().toString() to ConditionDetermination(false)
                    ),
                    cost = { 3.0 },
                )
            }
        }

        @Test
        fun `scalability testing`() {
            val planner = AStarGoapPlanner(EmptyWorldStateDeterminer)
            val paddedActions = actions + generateRandomActions(300)
            val (plan, ms) = time {
                planner.planToGoal(paddedActions, getAwayWithMurderGoal)
            }
            assertTrue(plan != null)
        }
    }

}
