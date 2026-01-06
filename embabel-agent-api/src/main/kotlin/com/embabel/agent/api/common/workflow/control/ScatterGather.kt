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
package com.embabel.agent.api.common.workflow.control

import com.embabel.agent.api.common.SupplierActionContext
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.support.SupplierAction
import com.embabel.agent.api.common.support.TransformationAction
import com.embabel.agent.api.dsl.TypedAgentScopeBuilder
import com.embabel.agent.core.Action
import com.embabel.agent.core.Goal
import com.embabel.common.core.MobyNameGenerator
import org.slf4j.LoggerFactory

data class ResultList<RESULT : Any>(
    val results: List<RESULT>,
)

/**
 * Scatter gather pattern for generating multiple results in parallel and consolidating them.
 */
class ScatterGather(
    private val maxConcurrency: Int,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun <ELEMENT : Any, RESULT : Any> forkJoin(
        generators: List<(SupplierActionContext<ELEMENT>) -> ELEMENT>,
        joinFunction: (TransformationActionContext<ResultList<ELEMENT>, RESULT>) -> RESULT,
        elementClass: Class<ELEMENT>,
        resultClass: Class<RESULT>,
    ): TypedAgentScopeBuilder<RESULT> {
        val generateAction = SupplierAction(
            name = "=>${resultClass.name}",
            description = "Generate $resultClass",
            cost = { 0.0 },
            value = { 0.0 },
            canRerun = true,
            outputClass = ResultList::class.java,
            toolGroups = emptySet(),
        ) { context ->
            logger.info("Generating results using {} generators", generators.size)
            val tac = SupplierActionContext(
                processContext = context.processContext,
                outputClass = elementClass,
                action = context.action,
            )
            val results = context.parallelMap(generators, maxConcurrency = maxConcurrency) { generator ->
                val result = generator.invoke(tac)
                logger.info("Generated result: {}", result)
                result
            }
            ResultList(results)
        }

        val consolidateAction: Action = TransformationAction(
            name = "consolidate-${resultClass.name}",
            description = "Consolidate results and feedback",
            cost = { 0.0 },
            value = { 0.0 },
            toolGroups = emptySet(),
            inputClass = ResultList::class.java,
            outputClass = resultClass,
        ) { context ->
            val finalResult = joinFunction.invoke(
                context as TransformationActionContext<ResultList<ELEMENT>, RESULT>,
            )
            logger.info("Consolidating results, final (best) result: {}", finalResult)
            finalResult
        }

        val resultGoal = Goal(
            name = "final-${resultClass.name}",
            description = "Satisfied with the final ${resultClass.name}",
            satisfiedBy = resultClass,
        )
        logger.info("Created goal: {}", resultGoal.infoString(verbose = true, indent = 2))

        return TypedAgentScopeBuilder(
            name = MobyNameGenerator.generateName(),
            actions = listOf(
                generateAction,
                consolidateAction,
            ),
            goals = setOf(resultGoal),
            opaque = true,
        )
    }

}
