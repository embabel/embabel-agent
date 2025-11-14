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

import java.util.*

/**
 * Implements a Goal-Oriented Action Planning system using the A* algorithm.
 * A* works by finding the optimal sequence of actions to transform an initial state into a goal state
 * while minimizing total cost.
 * See https://en.wikipedia.org/wiki/A*_search_algorithm
 *
 * The algorithm works as follows:
 * 1. Start with the initial world state
 * 2. Maintain an open list (priority queue) of states to explore, prioritized by f-score
 * 3. For each state, explore all achievable actions, calculating:
 *    - g-score: The cost accumulated so far to reach this state
 *    - h-score: A heuristic estimate of the remaining cost to reach the goal
 *    - f-score: g-score + h-score (total estimated cost)
 * 4. Always expand the state with the lowest f-score first
 * 5. Track visited states and their best known costs to avoid cycles and redundant exploration
 * 6. Continue until finding a state that satisfies the goal conditions
 *
 * The implementation ensures finding the optimal (lowest cost) sequence of actions
 * by properly tracking path costs and using an admissible heuristic function.
 */
class AStarGoapPlanner(worldStateDeterminer: WorldStateDeterminer) :
    OptimizingGoapPlanner(worldStateDeterminer) {

    override fun planToGoalFrom(
        startState: GoapWorldState,
        actions: Collection<GoapAction>,
        goal: GoapGoal,
    ): GoapPlan? {
        // Quick check: if goal is already satisfied, return empty plan
        if (goal.isAchievable(startState)) {
            return GoapPlan(emptyList(), goal, worldState = startState)
        }

        // Early reachability check to avoid expensive A* search for unreachable goals
        if (!isGoalReachable(startState, actions, goal)) {
            return null
        }

        // Open list - states to be evaluated
        val openList = PriorityQueue<SearchNode>()

        // Maps each state to its best known cost
        val gScores = mutableMapOf<GoapWorldState, Double>().withDefault { Double.MAX_VALUE }

        // Maps each state to its best predecessor state and action
        val cameFrom = mutableMapOf<GoapWorldState, Pair<GoapWorldState, GoapAction?>>()

        // Set to track states that have been fully evaluated
        val closedSet = mutableSetOf<GoapWorldState>()

        // Initialize with start node
        gScores[startState] = 0.0
        openList.add(SearchNode(startState, 0.0, heuristic(startState, goal)))

        // Track the best goal state found so far
        var bestGoalNode: SearchNode? = null
        var bestGoalScore = Double.MAX_VALUE

        // Track number of iterations to prevent potential infinite loops
        var iterationCount = 0
        val maxIterations = 10000 // Adjust as needed

        while (openList.isNotEmpty() && iterationCount < maxIterations) {
            iterationCount++
            val current = openList.poll()

            // If we've already found a goal state with a better score, we can skip this node
            if (bestGoalNode != null && current.gScore >= bestGoalScore) {
                continue
            }

            // Skip if we've already processed this state
            if (current.state in closedSet) continue

            // Mark as processed
            closedSet.add(current.state)

            // Check if this is a goal state
            if (goal.isAchievable(current.state)) {
                // Only update if this is a better goal state than we've found before
                if (bestGoalNode == null || current.gScore < bestGoalScore) {
                    bestGoalNode = current
                    bestGoalScore = current.gScore
                }
                continue // No need to explore further from this goal state
            }

            // Try each possible action from the current state
            // Sort actions by number of preconditions (descending) to prefer more specific actions
            val sortedActions = actions.sortedByDescending { it.preconditions.size }
            for (action in sortedActions) {
                if (!action.isAchievable(current.state)) continue

                // Calculate the new state after applying this action
                val nextState = applyAction(current.state, action)

                // Skip if this action doesn't actually change the state (prevents loops)
                if (nextState == current.state) continue

                // Calculate total cost to reach nextState via this path
                val tentativeGScore = gScores.getValue(current.state) + action.cost(startState)

                // Skip if this path would already be more expensive than our best goal so far
                if (bestGoalNode != null && tentativeGScore >= bestGoalScore) {
                    continue
                }

                // If we found a better path to nextState
                if (tentativeGScore < gScores.getValue(nextState)) {
                    // Record this better path
                    cameFrom[nextState] = Pair(current.state, action)
                    gScores[nextState] = tentativeGScore

                    // Only add to open list if not in closed set, or if we've found a better path
                    if (nextState !in closedSet) {
                        openList.add(SearchNode(nextState, tentativeGScore, heuristic(nextState, goal)))
                    } else {
                        // If we find a better path to a "closed" state, reopen it
                        closedSet.remove(nextState)
                        openList.add(SearchNode(nextState, tentativeGScore, heuristic(nextState, goal)))
                    }
                }
            }
        }

        // If we found a goal state, reconstruct and optimize the plan
        if (bestGoalNode != null) {
            val plan = reconstructPath(cameFrom, bestGoalNode.state)

            // First pass: apply aggressive backward planning optimization
            val optimizedPlan = backwardPlanningOptimization(plan, startState, goal)

            // Second pass: remove any actions that don't contribute to the goal
            val finalPlan = forwardPlanningOptimization(optimizedPlan, startState, goal)

            return GoapPlan(finalPlan, goal, worldState = startState)
        }

        // No path found
        return null
    }

    /**
     * Backward planning optimization - works backward from the goal,
     * including only actions that contribute to achieving the goal.
     */
    private fun backwardPlanningOptimization(
        plan: List<GoapAction>,
        startState: GoapWorldState,
        goal: GoapGoal,
    ): List<GoapAction> {
        if (plan.isEmpty()) return plan

        // Start with the goal conditions we need to satisfy
        val targetConditions = goal.preconditions.toMutableMap()

        // Work backward from the end of the plan
        val keptActions = mutableListOf<GoapAction>()

        // Process actions in reverse
        for (action in plan.reversed()) {
            var isNecessary = false

            // Check if this action establishes any needed condition
            for ((key, value) in action.effects) {
                if (targetConditions[key] == value) {
                    isNecessary = true

                    // Remove this condition from our targets (it's handled)
                    targetConditions.remove(key)

                    // Add any preconditions this action needs
                    action.preconditions.forEach { (precKey, precValue) ->
                        targetConditions[precKey] = precValue
                    }
                }
            }

            if (isNecessary) {
                keptActions.add(action)
            }
        }

        // Reverse back to the correct order
        return keptActions.reversed()
    }

    /**
     * Forward planning optimization - simulates the plan and removes any actions
     * that don't contribute to achieving the goal.
     */
    private fun forwardPlanningOptimization(
        plan: List<GoapAction>,
        startState: GoapWorldState,
        goal: GoapGoal,
    ): List<GoapAction> {
        if (plan.isEmpty()) return plan

        val optimizedPlan = mutableListOf<GoapAction>()
        var currentState = startState

        // For each action in the plan
        for (action in plan) {
            // Skip if action isn't achievable in current state
            if (!action.isAchievable(currentState)) continue

            // Apply this action
            val nextState = applyAction(currentState, action)

            // Check if this action makes progress toward the goal
            val progressMade = nextState != currentState &&
                    action.effects.any { (key, value) ->
                        goal.preconditions.containsKey(key) &&
                                currentState.state[key] != goal.preconditions[key] &&
                                (value == goal.preconditions[key] || key !in nextState.state)
                    }

            if (progressMade) {
                optimizedPlan.add(action)
                currentState = nextState
            }
        }

        // Ensure our optimized plan still achieves the goal
        val finalState = simulatePlan(startState, optimizedPlan)
        if (!goal.isAchievable(finalState) && plan.isNotEmpty()) {
            // Our optimization was too aggressive, return the original input plan
            return plan
        }

        return optimizedPlan
    }

    /**
     * Simulates applying a sequence of actions to a starting state and returns the final state.
     */
    private fun simulatePlan(
        startState: GoapWorldState,
        actions: List<GoapAction>,
    ): GoapWorldState {
        var currentState = startState
        for (action in actions) {
            if (action.isAchievable(currentState)) {
                currentState = applyAction(currentState, action)
            }
        }
        return currentState
    }

    /**
     * Performs a fast backward reachability analysis to determine if a goal is theoretically reachable.
     * This check is conservative - it only returns false for OBVIOUSLY unreachable goals.
     * If there's any doubt, it returns true and lets the A* search determine actual reachability.
     *
     * The key optimization: quickly detect when a goal requires an effect that no action can produce.
     */
    private fun isGoalReachable(
        startState: GoapWorldState,
        actions: Collection<GoapAction>,
        goal: GoapGoal,
    ): Boolean {
        // Build a set of all effects that actions can produce
        val producibleEffects = mutableSetOf<Pair<String, ConditionDetermination>>()
        for (action in actions) {
            for ((key, value) in action.effects) {
                producibleEffects.add(key to value)
            }
        }

        // Check each goal precondition
        for ((key, value) in goal.preconditions) {
            // If already satisfied in start state, skip
            if (startState.state[key] == value) {
                continue
            }

            // If no action can produce this effect, goal is unreachable
            if ((key to value) !in producibleEffects) {
                return false
            }
        }

        // Goal might be reachable - let A* determine for sure
        return true
    }

    /**
     * Heuristic function that estimates the cost to reach the goal from the given state.
     * This implementation counts the number of unsatisfied conditions.
     * The heuristic is admissible (never overestimates) which ensures A* finds the optimal path.
     */
    private fun heuristic(
        state: GoapWorldState,
        goal: GoapGoal,
    ): Double {
        return goal.preconditions.count { (key, value) -> state.state[key] != value }.toDouble()
    }

    /**
     * Apply an action to a state, returning the resulting new state.
     */
    private fun applyAction(
        currentState: GoapWorldState,
        action: GoapAction,
    ): GoapWorldState {
        val newState = currentState.state.toMutableMap()
        action.effects.forEach { (key, value) ->
            newState[key] = value
        }
        return GoapWorldState(newState as HashMap<String, ConditionDetermination>)
    }

    /**
     * Reconstruct the path from the start state to the goal state using the recorded actions.
     */
    private fun reconstructPath(
        cameFrom: Map<GoapWorldState, Pair<GoapWorldState, GoapAction?>>,
        goalState: GoapWorldState,
    ): List<GoapAction> {
        val actions = mutableListOf<GoapAction>()
        var currentState = goalState

        while (currentState in cameFrom) {
            val (previousState, action) = cameFrom[currentState]!!
            if (action != null) {
                actions.add(action)
            }
            currentState = previousState
        }

        return actions.reversed()
    }
}

/**
 * Represents a node in the A* search algorithm.
 * Contains the state, its g-score (cost from start), and h-score (heuristic estimate to goal).
 */
private class SearchNode(
    val state: GoapWorldState,
    val gScore: Double,
    val hScore: Double,
) : Comparable<SearchNode> {
    // Calculate f-score (total estimated cost to goal)
    private val fScore: Double = gScore + hScore

    override fun compareTo(other: SearchNode): Int {
        return when {
            fScore < other.fScore -> -1
            fScore > other.fScore -> 1
            else -> 0
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SearchNode) return false
        return state == other.state
    }

    override fun hashCode(): Int {
        return state.hashCode()
    }
}
