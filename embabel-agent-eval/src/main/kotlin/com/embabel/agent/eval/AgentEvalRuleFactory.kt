package com.example.agenteval

import com.embabel.agent.core.AgentProcess
import com.example.agenteval.rules.ActionRules
import com.example.agenteval.rules.CostRules
import com.example.agenteval.rules.StatusRules
import com.example.agenteval.rules.TimingRules

/**
 * Aggregates the predefined rule families into a named registry.
 * Rule defaults live in each rules object and are overridable per call.
 */
class AgentEvalRuleFactory {

    val rules: Map<String, AgentEvalRule> =
        StatusRules.named + ActionRules.named + TimingRules.named + CostRules.named

    fun byName(name: String): AgentEvalRule = rules.getValue(name)

    /** Evaluate every predefined rule — a full eval report for one run. */
    fun evaluateAll(process: AgentProcess): Map<String, Boolean> =
        rules.mapValues { (_, rule) -> rule.evaluate(process) }
}