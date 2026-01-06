/*
 * Copyright 2024-2026 Embabel Pty Ltd.
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
package com.embabel.agent.api.common.workflow.loop

import com.embabel.agent.api.common.workflow.WorkflowBuilder
import com.embabel.agent.api.common.workflow.WorkflowBuilderConsuming
import com.embabel.agent.api.common.workflow.WorkflowBuilderReturning
import com.embabel.agent.api.dsl.TypedAgentScopeBuilder

/**
 * Java friendly builder for RepeatUntilAcceptable workflow.
 */
data class RepeatUntilAcceptableBuilder<INPUT, RESULT : Any, FEEDBACK : Feedback>(
    private val resultClass: Class<RESULT>,
    private val inputClass: Class<out INPUT>,
    private val feedbackClass: Class<FEEDBACK> = Feedback::class.java as Class<FEEDBACK>,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val scoreThreshold: Double = DEFAULT_SCORE_THRESHOLD,
) : WorkflowBuilderConsuming {

    companion object : WorkflowBuilderReturning {

        const val DEFAULT_MAX_ITERATIONS = 5

        const val DEFAULT_SCORE_THRESHOLD = 0.9

        /**
         * Create a RepeatUntilBuilder for a specific result type and default TextFeedback.
         */
        @JvmStatic
        override fun <RESULT : Any> returning(resultClass: Class<RESULT>): RepeatUntilAcceptableBuilder<Any?, RESULT, TextFeedback> {
            return RepeatUntilAcceptableBuilder(
                resultClass = resultClass,
                inputClass = Unit::class.java,
                feedbackClass = TextFeedback::class.java
            )
        }
    }

    override fun <INPUT : Any> consuming(inputClass: Class<INPUT>): RepeatUntilAcceptableBuilder<INPUT, RESULT, FEEDBACK> {
        return RepeatUntilAcceptableBuilder(
            resultClass = resultClass,
            inputClass = inputClass,
            feedbackClass = feedbackClass,
            maxIterations = maxIterations,
            scoreThreshold = scoreThreshold,
        )
    }

    /**
     * Customize the feedback class for this RepeatUntil workflow.
     */
    fun <F : Feedback> withFeedbackClass(feedbackClass: Class<F>): RepeatUntilAcceptableBuilder<INPUT, RESULT, F> =
        RepeatUntilAcceptableBuilder(
            resultClass = resultClass,
            inputClass = inputClass,
            feedbackClass = feedbackClass,
            maxIterations = maxIterations,
            scoreThreshold = scoreThreshold,
        )

    fun withMaxIterations(maxIterations: Int): RepeatUntilAcceptableBuilder<INPUT, RESULT, FEEDBACK> =
        copy(maxIterations = maxIterations)

    fun withScoreThreshold(scoreThreshold: Double): RepeatUntilAcceptableBuilder<INPUT, RESULT, FEEDBACK> =
        copy(scoreThreshold = scoreThreshold)

    /**
     * Define the task to be repeated until an acceptable result is achieved.
     */
    fun repeating(
        what: (RepeatUntilAcceptableActionContext<INPUT, RESULT, FEEDBACK>) -> RESULT,
    ): Critiquer {
        return Critiquer(generator = what)
    }

    inner class Critiquer(
        private val generator: (RepeatUntilAcceptableActionContext<INPUT, RESULT, FEEDBACK>) -> RESULT,
    ) {

        /**
         * Provide the evaluation function that will assess the generated results.
         */
        fun withEvaluator(
            evaluator: (EvaluationActionContext<INPUT, RESULT, FEEDBACK>) -> FEEDBACK,
        ): Evaluator {
            return Evaluator(generator = generator, evaluator = evaluator)
        }

    }

    inner class Evaluator(
        private val generator: (RepeatUntilAcceptableActionContext<INPUT, RESULT, FEEDBACK>) -> RESULT,
        private val evaluator: (EvaluationActionContext<INPUT, RESULT, FEEDBACK>) -> FEEDBACK,
    ) : WorkflowBuilder<RESULT>(resultClass = resultClass, inputClass = inputClass) {

        /**
         * Define the acceptance criteria for the feedback.
         * This will determine when the generated result is considered acceptable.
         */
        fun withAcceptanceCriteria(
            accept: (AcceptanceActionContext<INPUT, RESULT, FEEDBACK>) -> Boolean,
        ): Emitter {
            return Emitter(generator, evaluator, accept)
        }

        /**
         * Build an instance with default acceptance criteria,
         * based on threshold score
         */
        override fun build(): TypedAgentScopeBuilder<RESULT> {
            return withAcceptanceCriteria { it.feedback.score >= scoreThreshold }
                .build()
        }
    }

    inner class Emitter(
        private val generator: (RepeatUntilAcceptableActionContext<INPUT, RESULT, FEEDBACK>) -> RESULT,
        private val evaluator: (EvaluationActionContext<INPUT, RESULT, FEEDBACK>) -> FEEDBACK,
        private val accept: (AcceptanceActionContext<INPUT, RESULT, FEEDBACK>) -> Boolean,
    ) : WorkflowBuilder<RESULT>(resultClass = resultClass, inputClass = inputClass) {

        /**
         * Build the workflow so it can be included in agents
         */
        override fun build(): TypedAgentScopeBuilder<RESULT> {
            return RepeatUntilAcceptable(maxIterations = maxIterations, scoreThreshold = scoreThreshold)
                .build(
                    task = generator,
                    evaluator = evaluator,
                    acceptanceCriteria = accept,
                    resultClass = resultClass,
                    feedbackClass = feedbackClass,
                    inputClass = inputClass,
                )
        }

    }
}
