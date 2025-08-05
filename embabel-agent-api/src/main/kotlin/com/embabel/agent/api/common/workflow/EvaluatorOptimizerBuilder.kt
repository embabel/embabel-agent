package com.embabel.agent.api.common.workflow

import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.dsl.AgentScopeBuilder

class EvaluatorOptimizerBuilder<RESULT : Any, FEEDBACK : Feedback>(
    private val resultClass: Class<RESULT>,
    private val feedbackClass: Class<FEEDBACK>,
) {

    fun withGenerator(
        generator: (TransformationActionContext<FEEDBACK?, RESULT>) -> RESULT,
    ): Critiquer {
        return Critiquer(generator = generator)
    }

    inner class Critiquer(
        private val generator: (TransformationActionContext<FEEDBACK?, RESULT>) -> RESULT,
    ) {

        fun withEvaluator(
            evaluator: (TransformationActionContext<RESULT, FEEDBACK>) -> FEEDBACK,
        ): Umpire {
            return Umpire(generator = generator, evaluator = evaluator)
        }
    }

    inner class Umpire(
        private val generator: (TransformationActionContext<FEEDBACK?, RESULT>) -> RESULT,
        private val evaluator: (TransformationActionContext<RESULT, FEEDBACK>) -> FEEDBACK,
    ) {

        fun withAcceptanceCriteria(
            accept: (f: FEEDBACK) -> Boolean,
        ): Accepter {
            return Accepter(generator, evaluator, accept)
        }
    }

    inner class Accepter(
        private val generator: (TransformationActionContext<FEEDBACK?, RESULT>) -> RESULT,
        private val evaluator: (TransformationActionContext<RESULT, FEEDBACK>) -> FEEDBACK,
        private val accept: (f: FEEDBACK) -> Boolean,
        private val maxIterations: Int = 3,
    ) {

        fun withMaxIterations(maxIterations: Int) = Accepter(
            generator = generator,
            evaluator = evaluator,
            accept = accept,
            maxIterations = maxIterations,
        )

        fun build(): AgentScopeBuilder<ScoredResult<RESULT, FEEDBACK>> {
            return EvaluatorOptimizer.generateUntilAcceptable(
                generator = generator,
                evaluator = evaluator,
                acceptanceCriteria = accept,
                maxIterations = maxIterations,
                resultClass = resultClass,
                feedbackClass = feedbackClass,
            )
        }
    }
}