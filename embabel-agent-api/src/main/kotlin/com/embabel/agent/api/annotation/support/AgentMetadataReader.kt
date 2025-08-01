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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.*
import com.embabel.agent.api.common.EvaluateConditionPromptException
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.StuckHandler
import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.core.AgentScope
import com.embabel.agent.core.ComputedBooleanCondition
import com.embabel.agent.core.Export
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.support.Rerun
import com.embabel.agent.core.support.safelyGetToolCallbacksFrom
import com.embabel.agent.validation.AgentStructureValidator
import com.embabel.agent.validation.AgentValidationManager
import com.embabel.agent.validation.DefaultAgentValidationManager
import com.embabel.agent.validation.GoapPathToCompletionValidator
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.Semver
import com.embabel.common.util.NameUtils
import com.embabel.common.util.loggerFor
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.slf4j.LoggerFactory
import org.springframework.context.support.StaticApplicationContext
import org.springframework.stereotype.Service
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method
import com.embabel.agent.core.Action as CoreAction
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.Condition as CoreCondition
import com.embabel.agent.core.Goal as AgentCoreGoal

/**
 * Agentic info about a type
 */
data class AgenticInfo(
    val type: Class<*>,
) {

    val agentCapabilitiesAnnotation: AgentCapabilities? = type.getAnnotation(AgentCapabilities::class.java)
    val agentAnnotation: Agent? = type.getAnnotation(Agent::class.java)

    /**
     * Is this type agentic at all?
     */
    fun agentic() = agentCapabilitiesAnnotation != null || agentAnnotation != null

    fun validationErrors(): Collection<String> {
        val errors = mutableListOf<String>()
        if (agentCapabilitiesAnnotation != null && agentAnnotation != null) {
            errors += "Both @Agentic and @Agent annotations found on ${type.name}. Treating class as Agent, but both should not be used"
        }
        if (agentAnnotation != null && agentAnnotation.description.isBlank()) {
            errors + "No description provided for @${Agent::class.java.simpleName} on ${type.name}"
        }
        return errors
    }

    fun noAutoScan() = agentCapabilitiesAnnotation?.scan == false || agentAnnotation?.scan == false
}

/**
 * Read AgentMetadata from annotated classes.
 * Looks for @Agentic, @Condition and @Action annotations
 * and properties of type Goal.
 * Warn on invalid or missing annotations but never throw an exception
 * as this could affect application startup.
 */
@Service
class AgentMetadataReader(
    private val actionMethodManager: ActionMethodManager = DefaultActionMethodManager(),
    private val nameGenerator: MethodDefinedOperationNameGenerator = MethodDefinedOperationNameGenerator(),
    private val agentStructureValidator: AgentStructureValidator,
    private val goapPathToCompletionValidator: GoapPathToCompletionValidator
) {
    // test-friendly constructor
    constructor() : this(
        DefaultActionMethodManager(),
        MethodDefinedOperationNameGenerator(),
        AgentStructureValidator(StaticApplicationContext()),
        GoapPathToCompletionValidator()
    )

    private val logger = LoggerFactory.getLogger(AgentMetadataReader::class.java)

    private val agentValidationManager: AgentValidationManager = DefaultAgentValidationManager(
        listOf(
            agentStructureValidator,
            goapPathToCompletionValidator
        )
    )

    fun createAgentScopes(vararg instances: Any): List<AgentScope> =
        instances.mapNotNull { createAgentMetadata(it) }


    /**
     * Given this configured instance, find all the methods annotated with @Action and @Condition
     * The instance will have been injected by Spring if it's Spring-managed.
     * @return null if the class doesn't satisfy the requirements of @Agentic
     * or doesn't have the annotation at all.
     * @return an Agent if the class has the @Agent annotation,
     * otherwise the AgentMetadata superinterface
     */
    fun createAgentMetadata(instance: Any): AgentScope? {
        if (instance is Class<*>) {
            logger.warn(
                "❓Call to createAgentMetadata with class {}. Pass an instance",
                instance.name,
            )
            return null
        }

        val agenticInfo = AgenticInfo(instance.javaClass)
        if (!agenticInfo.agentic()) {
            logger.debug(
                "No @{} or @{} annotation found on {}",
                AgentCapabilities::class.simpleName,
                Agent::class.simpleName,
                agenticInfo.type.name,
            )
            return null
        }

        if (agenticInfo.validationErrors().isNotEmpty()) {
            logger.warn(
                agenticInfo.validationErrors().joinToString("\n"),
                AgentCapabilities::class.simpleName,
                Agent::class.simpleName,
                agenticInfo.type.name,
            )
            return null
        }

        val getterGoals = findGoalGetters(agenticInfo.type).map { getGoal(it, instance) }
        val actionMethods = findActionMethods(agenticInfo.type)
        val conditionMethods = findConditionMethods(agenticInfo.type)

        val toolCallbacksOnInstance = safelyGetToolCallbacksFrom(ToolObject.from(instance))

        val conditions = conditionMethods.map { createCondition(it, instance) }.toSet()
        val (actions, actionGoals) = actionMethods.map { actionMethod ->
            val action = actionMethodManager.createAction(actionMethod, instance, toolCallbacksOnInstance)
            Pair(action, createGoalFromActionMethod(actionMethod, action, instance))
        }.unzip()

        val goals = getterGoals + actionGoals.filterNotNull()

        if (actionMethods.isEmpty() && goals.isEmpty() && conditionMethods.isEmpty()) {
            logger.warn(
                "❓No methods annotated with @{} or @{} and no goals defined on {}",
                Action::class.simpleName,
                Condition::class.simpleName,
                agenticInfo.type.name,
            )
            return null
        }

        val agent = if (agenticInfo.agentAnnotation != null) {
            CoreAgent(
                name = agenticInfo.agentAnnotation.name.ifBlank { agenticInfo.type.simpleName },
                provider = agenticInfo.agentAnnotation.provider.ifBlank {
                    instance.javaClass.`package`.name
                },
                description = agenticInfo.agentAnnotation.description,
                version = Semver(agenticInfo.agentAnnotation.version),
                conditions = conditions,
                actions = actions,
                goals = goals.toSet(),
                stuckHandler = instance as? StuckHandler,
            )
        } else {
            AgentScope(
                name = agenticInfo.type.name,
                conditions = conditions,
                actions = actions,
                goals = goals.toSet(),
            )
        }

        val validationResult = agentValidationManager.validate(agent)
        if (!validationResult.isValid) {
            logger.warn("Agent validation failed:\n${validationResult.errors.joinToString("\n")}")
            // TODO: Uncomment to strengthen validation and refactor the test if needed. Because some tests might fail.
            // return null
        }

        return agent
    }

    private fun findConditionMethods(type: Class<*>): List<Method> {
        val conditionMethods = mutableListOf<Method>()
        ReflectionUtils.doWithMethods(
            type,
            { method -> conditionMethods.add(method) },
            // Make sure we only get annotated methods from this type, not supertypes
            { method ->
                method.isAnnotationPresent(Condition::class.java) &&
                        type.declaredMethods.contains(method)
            })
        return conditionMethods
    }

    private fun findActionMethods(type: Class<*>): List<Method> {
        val actionMethods = mutableListOf<Method>()
        ReflectionUtils.doWithMethods(
            type,
            { method -> actionMethods.add(method) },
            // Make sure we only get annotated methods from this type, not supertypes
            { method ->
                method.isAnnotationPresent(Action::class.java) &&
                        type.declaredMethods.contains(method) &&
                        (!method.returnType.isInterface || hasRequiredJsonDeserializeAnnotationOnInterfaceReturnType(
                            method
                        ))
            })
        if (actionMethods.isEmpty()) {
            logger.debug("No methods annotated with @{} found in {}", Action::class.simpleName, type)
        }
        return actionMethods
    }

    private fun findGoalGetters(type: Class<*>): List<Method> {
        val goalGetters = mutableListOf<Method>()
        type.declaredMethods.forEach { method ->
            if (method.parameterCount == 0 &&
                method.returnType != Void.TYPE
            ) {
                if (AgentCoreGoal::class.java.isAssignableFrom(method.returnType)) {
                    goalGetters.add(method)
                }
            }
        }
        if (goalGetters.isEmpty()) {
            logger.debug("No goal getters found in {}", type)
        }
        return goalGetters
    }

    private fun getGoal(
        method: Method,
        instance: Any,
    ): AgentCoreGoal {
        // We need to change the name to be the property name
        val rawGoal = ReflectionUtils.invokeMethod(method, instance) as AgentCoreGoal
        return rawGoal.copy(
            name = nameGenerator.generateName(
                instance,
                NameUtils.beanMethodToPropertyName(method.name)
            )
        )
    }

    private fun createCondition(
        method: Method,
        instance: Any,
    ): ComputedBooleanCondition {
        val conditionAnnotation = method.getAnnotation(Condition::class.java)
        return ComputedBooleanCondition(
            name = conditionAnnotation.name.ifBlank {
                nameGenerator.generateName(instance, method.name)
            },
            cost = conditionAnnotation.cost,
        )
        { context, condition ->
            invokeConditionMethod(
                method = method,
                instance = instance,
                context = context,
                condition = condition,
            )
        }
    }

    private fun invokeConditionMethod(
        method: Method,
        instance: Any,
        condition: CoreCondition,
        context: OperationContext,
    ): Boolean {
        logger.debug("Invoking condition method {} on {}", method.name, instance.javaClass.name)
        val args = mutableListOf<Any>()

        for (parameter in method.parameters) {
            when {
                OperationContext::class.java.isAssignableFrom(parameter.type) -> {
                    args += context
                }

                else -> {
                    val requireNameMatch = parameter.getAnnotation(RequireNameMatch::class.java)
                    val domainTypes = context.agentProcess.agent.domainTypes
                    val variable = if (requireNameMatch != null) {
                        parameter.name
                    } else {
                        IoBinding.DEFAULT_BINDING
                    }
                    args += context.getValue(
                        variable = variable,
                        type = parameter.type.name,
                        domainTypes = domainTypes,
                    )
                        ?: return run {
                            // TODO assignable?
                            if (domainTypes.contains(parameter.type)) {
                                // This is not an error condition
                                logger.debug(
                                    "Condition method {}.{} has no value for parameter {} of known type {}: Returning false",
                                    instance.javaClass.name,
                                    method.name,
                                    variable,
                                    parameter.type,
                                )
                            } else {
                                logger.warn(
                                    "Condition method {}.{} has unsupported argument {}. Unknown type {}",
                                    instance.javaClass.name,
                                    method.name,
                                    variable,
                                    parameter.type,
                                )
                            }
                            false
                        }
                }
            }
        }
        return try {
            val evaluationResult = ReflectionUtils.invokeMethod(method, instance, *args.toTypedArray()) as Boolean
            logger.debug(
                "Condition evaluated to {}, calling {} on {} using args {}",
                evaluationResult,
                method.name,
                instance.javaClass.name,
                args,
            )
            evaluationResult
        } catch (ecpe: EvaluateConditionPromptException) {
            // This is our own exception to get typesafe prompt execution
            // It is not a failure
            val promptRunner = context.promptRunner(
                llm = ecpe.llm ?: LlmOptions(),
                promptContributors = ecpe.promptContributors,
                contextualPromptContributors = ecpe.contextualPromptContributors,
            )

            promptRunner.evaluateCondition(
                condition = ecpe.condition,
                context = ecpe.context,
                confidenceThreshold = ecpe.confidenceThreshold,
            )
        } catch (t: Throwable) {
            logger.warn("Error invoking condition method ${method.name} with args $args", t)
            false
        }
    }

    /**
     * If the @Action method also has an @AchievesGoal annotation,
     * create a goal from it.
     */
    private fun createGoalFromActionMethod(
        method: Method,
        action: CoreAction,
        instance: Any,
    ): AgentCoreGoal? {
        val actionAnnotation = method.getAnnotation(Action::class.java)
        val goalAnnotation = method.getAnnotation(AchievesGoal::class.java) ?: return null
        val inputBinding = IoBinding(
            name = actionAnnotation.outputBinding,
            type = method.returnType.name,
        )
        return AgentCoreGoal(
            name = nameGenerator.generateName(instance, method.name),
            description = goalAnnotation.description,
            inputs = setOf(inputBinding),
            value = goalAnnotation.value,
            // Add precondition of the action having run
            pre = setOf(Rerun.hasRunCondition(action)) + action.preconditions.keys.toSet(),
            export = Export(
                local = goalAnnotation.export.local,
                remote = goalAnnotation.export.remote,
                name = goalAnnotation.export.name.ifBlank { null },
                startingInputTypes = goalAnnotation.export.startingInputTypes.map { it.java }.toSet(),
            )
        )
    }
}

/**
 * Checks if a method returning an interface returns a type with a @JsonDeserialize annotation.
 * @param method The Java method to check.
 * @return true if the return type has a @JsonDeserialize annotation, false otherwise
 */
private fun hasRequiredJsonDeserializeAnnotationOnInterfaceReturnType(method: Method): Boolean {
    val hasRequiredAnnotation = method.returnType.isAnnotationPresent(JsonDeserialize::class.java)
    if (!hasRequiredAnnotation) {
        loggerFor<AgentMetadataReader>().warn(
            "❓Interface {} used as return type of {}.{} must have @JsonDeserialize annotation",
            method.returnType,
            method.declaringClass,
            method.name,
        )
    }
    return hasRequiredAnnotation
}
