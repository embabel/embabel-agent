package com.example.agenteval.rules

import com.embabel.agent.core.AgentProcessStatusCode
import com.example.agenteval.AgentEvalRule
import java.time.Duration

object TimingRules {

    val DEFAULT_SLOW: Duration = Duration.ofSeconds(30)
    const val DEFAULT_MAX_ACTIONS: Int = 20

    /** Process took longer than [max]. */
    fun tooSlow(max: Duration = DEFAULT_SLOW): AgentEvalRule =
        AgentEvalRule { it.runningTime > max }

    /** Process executed more than [max] actions. */
    fun tooManyActions(max: Int = DEFAULT_MAX_ACTIONS): AgentEvalRule =
        AgentEvalRule { it.history.size > max }

    fun succeededWithinTimeLimit(max: Duration): AgentEvalRule =
        AgentEvalRule { p ->
            p.status == AgentProcessStatusCode.COMPLETED && p.runningTime <= max
        }

    /** Every execution of [actionName] finished within [max] (diffed from history). */
    fun actionCompletedWithin(actionName: String, max: Duration): AgentEvalRule =
        AgentEvalRule { p ->
            val h = p.history
            val runs = h.withIndex().filter { it.value.actionName == actionName }
            runs.isNotEmpty() && runs.all { (i, inv) ->
                val start = if (i == 0) Duration.ZERO else h[i - 1].runningTime
                inv.runningTime - start <= max
            }
        }

    val named: Map<String, AgentEvalRule> = mapOf(
        "tooSlow" to tooSlow(),
        "tooManyActions" to tooManyActions(),
    )
}