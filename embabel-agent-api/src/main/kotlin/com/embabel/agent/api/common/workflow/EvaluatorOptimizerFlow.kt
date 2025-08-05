package com.embabel.agent.api.common.workflow

import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.dsl.AgentScopeBuilder
import com.embabel.agent.core.Agent


/**
 * Flow implementations are single-threaded so can hold state.
 * Records can also implement
 */
interface EvaluatorOptimizerFlow<RESULT : Any, FEEDBACK : Feedback> {

    fun generate(context: TransformationActionContext<FEEDBACK?, RESULT>): RESULT

    // TODO simple generator signature

    fun evaluate(context: TransformationActionContext<RESULT, FEEDBACK>): FEEDBACK

    fun judge(feedback: FEEDBACK): Boolean =
        feedback.score > .8

    fun maxIterations(): Int = 3

    fun resultClass(): Class<RESULT>

    fun feedbackClass(): Class<FEEDBACK> = Feedback::class.java as Class<FEEDBACK>
}


object Builders {

    fun <RESULT : Any, FEEDBACK : Feedback> EvaluatorOptimizerFlow<RESULT, FEEDBACK>.build(): AgentScopeBuilder<ScoredResult<RESULT, FEEDBACK>> {
        return EvaluatorOptimizer.generateUntilAcceptable(
            generator = ::generate,
            evaluator = ::evaluate,
            acceptanceCriteria = ::judge,
            maxIterations = maxIterations(),
            resultClass = resultClass(),
            feedbackClass = feedbackClass(),
        )
    }

    @JvmStatic
    fun <RESULT : Any, FEEDBACK : Feedback> createAgent(
        flow: EvaluatorOptimizerFlow<RESULT, FEEDBACK>,
        name: String,
        provider: String,
        description: String,
    ): Agent {
        return flow.build().build().createAgent(
            name = name,
            provider = "provider",
            description = description,
        )
    }


}