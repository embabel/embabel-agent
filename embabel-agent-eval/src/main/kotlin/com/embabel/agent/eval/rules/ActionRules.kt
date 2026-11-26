package com.example.agenteval.rules

import com.example.agenteval.AgentEvalRule

object ActionRules {

    /** Only actions declared on the agent definition were executed. */
    val onlyDeclaredActions = AgentEvalRule { p ->
        val declared = p.agent.actions.map { it.name }.toSet()
        p.history.all { it.actionName in declared }
    }

    fun executedAction(actionName: String): AgentEvalRule =
        AgentEvalRule { p -> p.history.any { it.actionName == actionName } }

    fun actionRanAtLeast(actionName: String, times: Int): AgentEvalRule =
        AgentEvalRule { p -> p.history.count { it.actionName == actionName } >= times }

    /** All given actions executed, any order; extras allowed. */
    fun followedActions(vararg actionNames: String): AgentEvalRule =
        AgentEvalRule { p ->
            val executed = p.history.map { it.actionName }
            actionNames.all { it in executed }
        }

    /** Given actions executed in this relative order; extras allowed. */
    fun followedActionsInOrder(actionNames: List<String>): AgentEvalRule =
        AgentEvalRule { p ->
            val executed = p.history.map { it.actionName }
            var i = 0
            for (a in executed) if (i < actionNames.size && a == actionNames[i]) i++
            i == actionNames.size
        }

    /** Strict plan adherence: exactly this sequence, nothing else. */
    fun followedExactPlan(actionNames: List<String>): AgentEvalRule =
        AgentEvalRule { p -> p.history.map { it.actionName } == actionNames }

    /** Nothing outside [allowed] was executed. */
    fun noUnexpectedActions(allowed: Set<String>): AgentEvalRule =
        AgentEvalRule { p -> p.history.all { it.actionName in allowed } }

    fun firstActionWas(name: String): AgentEvalRule =
        AgentEvalRule { p -> p.history.firstOrNull()?.actionName == name }

    fun lastActionWas(name: String): AgentEvalRule =
        AgentEvalRule { p -> p.history.lastOrNull()?.actionName == name }

    val named: Map<String, AgentEvalRule> = mapOf(
        "onlyDeclaredActions" to onlyDeclaredActions,
    )
}