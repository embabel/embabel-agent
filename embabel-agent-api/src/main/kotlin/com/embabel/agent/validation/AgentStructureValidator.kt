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
package com.embabel.agent.validation

import org.springframework.beans.factory.InitializingBean
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Condition
import com.embabel.agent.core.AgentScope

/**
 * Validator that checks the fundamental structure of agent definitions.
 *
 * Ensures that each agent has at least one goal, action, or condition defined,
 * and validates that action and condition method signatures are correct and conform to expected patterns.
 *
 * Specific checks include:
 * - Detecting empty agents (no actions, conditions, or goals)
 * - Enforcing that agents have at least one goal
 * - Verifying that action and condition methods have valid parameter signatures
 *
 * Reports detailed validation errors for structural issues, helping ensure that only well-formed
 * agents are registered or used in the system.
 */
@Component
class AgentStructureValidator(
    val context: ApplicationContext
) : InitializingBean, AgentValidator {

    override fun afterPropertiesSet() {
        val agentBeans = context.getBeansWithAnnotation(Agent::class.java)
        agentBeans.values.forEach { bean ->
            val clazz = getOriginalClass(bean.javaClass)
            val actionMethods = clazz.declaredMethods.filter { it.isAnnotationPresent(Action::class.java) }
            val conditionMethods = clazz.declaredMethods.filter { it.isAnnotationPresent(Condition::class.java) }
            val hasGoalsField = clazz.declaredFields.any { it.name == "goals" }
            val hasGoalsMethod = clazz.methods.any { it.name == "getGoals" }
            val hasGoals = hasGoalsField || hasGoalsMethod

            if (actionMethods.isEmpty() && conditionMethods.isEmpty() && !hasGoals) {
                val error = ValidationError(
                    code = "EMPTY_AGENT_STRUCTURE",
                    message = "Agent class '${clazz.name}' has no @Action or @Condition methods and no goals defined. This agent will NOT be registered!",
                    severity = ValidationSeverity.ERROR,
                    location = ValidationLocation(
                        type = "Agent",
                        name = clazz.name,
                        agentName = clazz.name,
                        component = clazz.name
                    )
                )
                throw IllegalStateException(error.toString())
            }
        }
    }

    override fun validate(agentScope: AgentScope): ValidationResult {
        val errors = mutableListOf<ValidationError>()

        // Check for methods and goals
        if (agentScope.actions.isEmpty() && agentScope.conditions.isEmpty() && agentScope.goals.isEmpty()) {
            errors.add(
                ValidationError(
                    code = "EMPTY_AGENT_STRUCTURE",
                    message = "Agent '${agentScope.name}' has no actions, conditions, or goals defined",
                    severity = ValidationSeverity.ERROR,
                    location = ValidationLocation(
                        type = "Agent",
                        name = agentScope.name,
                        agentName = agentScope.name,
                        component = agentScope.name
                    )
                )
            )
        }

        // Validate that the agent has at least one goal
        if (agentScope.goals.isEmpty()) {
            errors.add(
                ValidationError(
                    code = "MISSING_GOALS",
                    message = "Agent '${agentScope.name}' must have at least one goal defined",
                    severity = ValidationSeverity.ERROR,
                    location = ValidationLocation(
                        type = "Agent",
                        name = agentScope.name,
                        agentName = agentScope.name,
                        component = agentScope.name
                    )
                )
            )
        }

        // Validate action signatures
        agentScope.actions.forEach { action ->
            // Check if the action has any preconditions that require multiple parameters
            val preconditionsWithMultipleParams = action.preconditions.any { (preconditionName, _) ->
                val clazz = getOriginalClass(agentScope.javaClass)
                val method = clazz.declaredMethods.find { it.name == preconditionName }
                method?.parameterCount ?: 0 > 1
            }

            if (preconditionsWithMultipleParams) {
                errors.add(
                    ValidationError(
                        code = "INVALID_ACTION_SIGNATURE",
                        message = "Action '${action.name}' has preconditions with multiple parameters",
                        severity = ValidationSeverity.ERROR,
                        location = ValidationLocation(
                            type = "Action",
                            name = action.name,
                            agentName = agentScope.name,
                            component = agentScope.name
                        )
                    )
                )
            }
        }

        // Validate conditions
        agentScope.conditions.forEach { condition ->
            val clazz = getOriginalClass(agentScope.javaClass)
            val method = clazz.declaredMethods.find { it.name == condition.name }

            if (method?.parameterCount ?: 0 > 1) {
                errors.add(
                    ValidationError(
                        code = "INVALID_CONDITION_SIGNATURE",
                        message = "Condition '${condition.name}' must have at most one parameter",
                        severity = ValidationSeverity.ERROR,
                        location = ValidationLocation(
                            type = "Condition",
                            name = condition.name,
                            agentName = agentScope.name,
                            component = agentScope.name
                        )
                    )
                )
            }
        }

        return ValidationResult(errors.isEmpty(), errors)
    }

    // Unwraps proxy if needed (for classes proxied by Spring)
    private fun getOriginalClass(clazz: Class<*>): Class<*> {
        return if (clazz.name.contains("\$Proxy") || clazz.name.contains("CGLIB")) {
            clazz.superclass ?: clazz
        } else clazz
    }
}
