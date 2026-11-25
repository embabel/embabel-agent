package com.example.agenteval

import com.embabel.agent.core.AgentProcess

/** A single evaluation rule over an agent run. */
fun interface AgentEvalRule {
    fun evaluate(process: AgentProcess): Boolean
}

fun AgentEvalRule.and(other: AgentEvalRule): AgentEvalRule =
    AgentEvalRule { p -> evaluate(p) && other.evaluate(p) }

fun AgentEvalRule.or(other: AgentEvalRule): AgentEvalRule =
    AgentEvalRule { p -> evaluate(p) || other.evaluate(p) }

fun AgentEvalRule.not(): AgentEvalRule =
    AgentEvalRule { p -> !evaluate(p) }