package com.example.agenteval.rules

import com.embabel.agent.core.AgentProcessStatusCode
import com.example.agenteval.AgentEvalRule

object StatusRules {

    val isFailed = AgentEvalRule { it.status == AgentProcessStatusCode.FAILED }
    val isCompleted = AgentEvalRule { it.status == AgentProcessStatusCode.COMPLETED }
    val isStuck = AgentEvalRule { it.status == AgentProcessStatusCode.STUCK }
    val wasKilled = AgentEvalRule { it.status == AgentProcessStatusCode.KILLED }
    val didTerminate = AgentEvalRule { it.status == AgentProcessStatusCode.TERMINATED }
    val isFinished = AgentEvalRule { it.finished }
    val isStillRunning = AgentEvalRule { it.finished.not() }
    val hasFailureInfo = AgentEvalRule { it.failureInfo != null }

    fun achievedGoal(goalName: String): AgentEvalRule =
        AgentEvalRule { it.status == AgentProcessStatusCode.COMPLETED && it.goal?.name == goalName }

    val named: Map<String, AgentEvalRule> = mapOf(
        "isFailed" to isFailed,
        "isCompleted" to isCompleted,
        "isStuck" to isStuck,
        "wasKilled" to wasKilled,
        "didTerminate" to didTerminate,
        "isFinished" to isFinished,
        "isStillRunning" to isStillRunning,
        "hasFailureInfo" to hasFailureInfo,
    )
}