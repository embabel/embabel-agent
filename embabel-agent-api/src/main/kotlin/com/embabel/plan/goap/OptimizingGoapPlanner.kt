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

import com.embabel.common.util.loggerFor
import com.embabel.plan.Action
import com.embabel.plan.Goal

/**
 * Abstract class for a Goap planner with common optimization.
 */
abstract class OptimizingGoapPlanner(
    val worldStateDeterminer: WorldStateDeterminer,
) : GoapPlanner {

    override fun worldState(): GoapWorldState {
        return worldStateDeterminer.determineWorldState()
    }

    final override fun planToGoal(
        actions: Collection<Action>,
        goal: Goal,
    ): GoapPlan? {
        goal as GoapGoal
        val startState = worldState()
        val directPlan = planToGoalFrom(startState, actions.filterIsInstance<GoapAction>(), goal)

        val goapActions = actions.filterIsInstance<GoapAction>()

        // See if changing any unknown conditions could change the result
        val unknownConditions = startState.unknownConditions()
        if (unknownConditions.isNotEmpty()) {
            if (unknownConditions.size > 1) {
                TODO("Handle more than one unknown condition: Have $unknownConditions")
            }
            val condition = unknownConditions.single()
            val variants = startState.variants(condition)
            val allPossiblePlans = variants.map {
                planToGoalFrom(it, goapActions, goal)
            } + directPlan
            if (allPossiblePlans.filterNotNull().distinctBy { it.actions.joinToString { a -> a.name } }.size > 1) {
                // We need to evaluate the condition
                val fullyEvaluatedState = startState + (condition to worldStateDeterminer.determineCondition(condition))
                return planToGoalFrom(fullyEvaluatedState, goapActions, goal)
            }
        }

        // Just use direct plan
        return directPlan
    }

    /**
     * Enhanced pruning that combines backward chaining and multi-path pruning.
     * This prevents action leakage while preserving re-routing capabilities.
     */
    override fun prune(planningSystem: GoapPlanningSystem): GoapPlanningSystem {
        // Step 1: Use backward chaining to identify goal-relevant actions
        val goalRelevantActions = findActionsRelevantToGoals(planningSystem)
        
        // Step 2: Apply multi-path pruning to preserve re-routing capabilities
        val prunedWithMultiPath = applyMultiPathPruning(
            planningSystem.copy(actions = goalRelevantActions)
        )
        
        loggerFor<OptimizingGoapPlanner>().info(
            "Hybrid pruning: Started with ${planningSystem.actions.size} actions, " +
            "backward chaining reduced to ${goalRelevantActions.size}, " +
            "final count after multi-path pruning: ${prunedWithMultiPath.actions.size}"
        )
        
        return prunedWithMultiPath
    }
    
    /**
     * Find all actions that could contribute to achieving the specific goals.
     */
    private fun findActionsRelevantToGoals(planningSystem: GoapPlanningSystem): Set<GoapAction> {
        if (planningSystem.goals.size == 1) {
            return findActionsRelevantToSingleGoal(planningSystem, planningSystem.goals.first() as GoapGoal)
        }
        val relevantActions = mutableSetOf<GoapAction>()
        for (goal in planningSystem.goals) {
            goal as GoapGoal
            val actionsForGoal = findActionsRelevantToSingleGoal(planningSystem, goal)
            relevantActions.addAll(actionsForGoal)
        }
        
        return relevantActions
    }
    
    /**
     * Find actions relevant to a single goal using backward chaining.
     * This is a more strict implementation that only includes actions directly contributing to the goal.
     */
    private fun findActionsRelevantToSingleGoal(planningSystem: GoapPlanningSystem, goal: GoapGoal): Set<GoapAction> {
        val relevantActions = mutableSetOf<GoapAction>()

        val requiredConditions = goal.preconditions.keys.toMutableSet()
        val processedConditions = mutableSetOf<String>()
        
        // Continue until no new conditions are added
        while (requiredConditions.isNotEmpty()) {
            val condition = requiredConditions.first()
            requiredConditions.remove(condition)

            if (condition in processedConditions) {
                continue
            }
            processedConditions.add(condition)
            
            // Find actions that can establish this condition
            val actionsForCondition = planningSystem.actions.filter { action ->
                action.effects.any { effect -> 
                    effect.key == condition && effect.value == ConditionDetermination.TRUE 
                }
            }

            for (action in actionsForCondition) {
                relevantActions.add(action)
                action.preconditions.keys.forEach { precondition ->
                    if (precondition !in processedConditions) {
                        requiredConditions.add(precondition)
                    }
                }
            }
        }
        
        // Add re-routing actions that might be needed
        val reroutingActions = findPotentialReroutingActions(planningSystem, relevantActions)
        relevantActions.addAll(reroutingActions)
        
        return relevantActions
    }
    
    /**
     * Find potential re-routing actions that might be needed.
     * These are actions that don't directly contribute to the goal but might be needed for re-routing.
     */
    private fun findPotentialReroutingActions(
        planningSystem: GoapPlanningSystem, 
        directlyRelevantActions: Set<GoapAction>
    ): Set<GoapAction> {
        val reroutingActions = mutableSetOf<GoapAction>()
        
        // Get all conditions that might be needed for re-routing
        val relevantConditions = directlyRelevantActions.flatMap { 
            it.preconditions.keys + it.effects.keys 
        }.toSet()
        
        // Find actions that affect these conditions but aren't directly relevant
        for (action in planningSystem.actions) {
            if (action in directlyRelevantActions) {
                continue
            }
            
            // Check if this action affects any relevant condition
            val affectsRelevantCondition = action.effects.any { effect ->
                effect.key in relevantConditions
            }
            
            // Check if this action has similar preconditions to relevant actions
            val hasSimilarPreconditions = action.preconditions.any { precondition ->
                precondition.key in relevantConditions
            }
            
            // Check if this action has effects that aren't directly related to the goal
            // but might be needed for re-routing
            val hasNonGoalEffects = action.effects.any { effect ->
                !directlyRelevantActions.any { relevantAction ->
                    relevantAction.effects.containsKey(effect.key)
                }
            }
            
            if (affectsRelevantCondition || hasSimilarPreconditions || hasNonGoalEffects) {
                reroutingActions.add(action)
            }
        }
        
        return reroutingActions
    }
    
    /**
     * Multi-path pruning: Consider multiple possible paths to preserve re-routing capabilities.
     */
    private fun applyMultiPathPruning(planningSystem: GoapPlanningSystem): GoapPlanningSystem {
        val allPlans = plansToGoals(planningSystem)
        val alternativePlans = generateAlternativePlans(planningSystem)
        val combinedPlans = mutableListOf<GoapPlan>()
        combinedPlans.addAll(allPlans)
        combinedPlans.addAll(alternativePlans)
        loggerFor<OptimizingGoapPlanner>().info(
            "${combinedPlans.size} plan(s) to consider in multi-path pruning{}",
            if (combinedPlans.isEmpty())
                ""
            else
                ":" + combinedPlans.joinToString("\n") { it.infoString(true, 1) }
        )
        
        return planningSystem.copy(
            actions = planningSystem.actions.filter { action ->
                combinedPlans.any { plan ->
                    plan.actions.contains(action)
                }
            }.toSet(),
        )
    }
    
    /**
     * Generate alternative plans by considering different world states.
     */
    private fun generateAlternativePlans(planningSystem: GoapPlanningSystem): List<GoapPlan> {
        val alternativePlans = mutableListOf<GoapPlan>()
        
        // Get all conditions that might change during execution
        val conditions = planningSystem.actions.flatMap {
            it.effects.keys + it.preconditions.keys 
        }.toSet()
        
        // For each variable condition, create variants of the world state
        for (condition in conditions) {
            val currentState = worldState()
            if (condition !in currentState.state) {
                continue
            }
            // Create variants where this condition is flipped
            val variants = currentState.variants(condition)
            
            // For each variant, try to plan to each goal
            for (variant in variants) {
                for (goal in planningSystem.goals) {
                    goal as GoapGoal
                    val plan = planToGoalFrom(variant, planningSystem.actions, goal)
                    if (plan != null) {
                        alternativePlans.add(plan)
                    }
                }
            }
        }
        
        return alternativePlans
    }

    protected abstract fun planToGoalFrom(
        startState: GoapWorldState,
        actions: Collection<GoapAction>,
        goal: GoapGoal,
    ): GoapPlan?
}