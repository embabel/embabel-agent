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
package com.embabel.agent.core.support

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.Condition
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.satisfiesType
import com.embabel.agent.core.support.Rerun.HAS_RUN_CONDITION_PREFIX
import com.embabel.plan.goap.ConditionDetermination
import com.embabel.plan.goap.GoapWorldState
import com.embabel.plan.goap.WorldStateDeterminer
import org.slf4j.LoggerFactory

/**
 * Determine world state for the given ProcessContext,
 * using the blackboard.
 */
class BlackboardWorldStateDeterminer(
    private val processContext: ProcessContext,
) : WorldStateDeterminer {

    private val logger = LoggerFactory.getLogger(BlackboardWorldStateDeterminer::class.java)

    private val knownConditions = processContext.agentProcess.agent.planningSystem.knownConditions()

    override fun determineWorldState(): GoapWorldState {
        val map = mutableMapOf<String, ConditionDetermination>()
        knownConditions.forEach { condition ->
            // TODO shouldn't evaluate expensive conditions, just
            // return unknown
            map[condition] = determineCondition(condition)
        }
        return GoapWorldState(map)
    }

    override fun determineCondition(condition: String): ConditionDetermination {
        val conditionDetermination = when {
            // Data binding condition
            condition.contains(":") -> {
                val (variable, type) = condition.split(":")

                // If the variable is a map, we are satisfied by having the name bound
                // rather than checking the type
                // TODO may want to add type checking here
                val found = processContext.blackboard[variable]
                val maybeMap = found as? Map<*, *>
                if (maybeMap != null) {
                    return ConditionDetermination(true)
                }

                val value = processContext.getValue(variable, type)

                val determination = when {
                    type == "List" ->
                        value != null && (value is List<*>)

                    variable == "all" -> true // TODO fix this
                    else -> {
                        val determination =
                            value != null && satisfiesType(value, type)
                        logger.debug(
                            "Determined binding condition {}={}: variable={}, type={}, value={}",
                            condition,
                            determination,
                            variable,
                            type,
                            value,
                        )
                        determination
                    }
                }
                ConditionDetermination(determination)
            }

            condition.startsWith(Rerun.HAS_RUN_CONDITION_PREFIX) -> {
                // Special case for hasRun- conditions
                val actionName = condition.substringAfter(HAS_RUN_CONDITION_PREFIX)
                val determination = ConditionDetermination(processContext.agentProcess.history.any {
                    it.actionName == actionName
                })
                logger.debug(
                    "Determined hasRun condition {}={}: known conditions={}, bindings={}",
                    condition,
                    determination,
                    knownConditions.sorted(),
                    processContext.blackboard.infoString(),
                )
                determination
            }

            // Well known conditions, defined for reuse, with their own evaluation function
            knownConditions.any { knownCondition -> knownCondition == condition } &&
                    resolveAsAgentCondition(condition) != null -> {
                val condition = resolveAsAgentCondition(condition)!!

                val determination = condition.evaluate(
                    OperationContext(
                        processContext = processContext,
                        operation = condition,
                        toolGroups = emptySet(),
                    )
                )
                logger.debug(
                    "Determined known condition {}={}, bindings={}",
                    condition,
                    determination,
                    processContext.blackboard.infoString(),
                )
                determination
            }

            // Maybe the condition was explicitly set
            // In this case if it isn't set, we assume it is false
            // rather than unknown
            else -> {
                val determination = ConditionDetermination(processContext.blackboard.getCondition(condition))
                    .asTrueOrFalse()
                logger.debug(
                    "Looked for explicitly set condition: determined condition {}={}: known conditions={}, bindings={}",
                    condition,
                    determination,
                    knownConditions.sorted(),
                    processContext.blackboard.infoString(),
                )
                determination
            }
        }
        if (conditionDetermination == ConditionDetermination.UNKNOWN) {
            logger.warn(
                "Determined condition {} to be unknown: knownConditions={}, bindings={}",
                condition,
                knownConditions.sorted(),
                processContext.blackboard.infoString(),
            )
        }
        return conditionDetermination
    }

    private fun resolveAsAgentCondition(condition: String): Condition? {
        // Match FQN condition
        return processContext.agentProcess.agent.conditions.find {
            it.name == condition || it.name.endsWith(
                ".$condition"
            )
        }
    }
}
