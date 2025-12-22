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
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.common.StuckHandler
import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.core.*
import com.embabel.agent.core.Export
import com.embabel.agent.core.support.NIRVANA
import com.embabel.agent.core.support.Rerun
import com.embabel.agent.core.support.safelyGetToolCallbacksFrom
import com.embabel.agent.spi.validation.*
import com.embabel.common.core.types.Semver
import com.embabel.common.util.NameUtils
import com.embabel.common.util.loggerFor
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallback
import org.springframework.cglib.proxy.Enhancer
import org.springframework.stereotype.Service
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import com.embabel.agent.core.Action as CoreAction
import com.embabel.agent.core.Agent as CoreAgent
import com.embabel.agent.core.Condition as CoreCondition
import com.embabel.agent.core.Goal as AgentCoreGoal

/**
 * Agentic info about a type
 */
internal data class AgenticInfo(
    val type: Class<*>,
) {

    // Unwrap proxy to get target class for annotation lookups
    private val targetType: Class<*> = if (Enhancer.isEnhanced(type) ||
        Proxy.isProxyClass(type)
    ) {
        ClassUtils.getUserClass(type)
    } else {
        type
    }

    val embabelComponentAnnotation: EmbabelComponent? = targetType.getAnnotation(EmbabelComponent::class.java)
    val agentAnnotation: Agent? = targetType.getAnnotation(Agent::class.java)

    fun isAgent(): Boolean = agentAnnotation != null

    /**
     * Is this type agentic at all?
     */
    fun agentic() = embabelComponentAnnotation != null || agentAnnotation != null

    fun validationErrors(): Collection<String> {
        val errors = mutableListOf<String>()
        if (embabelComponentAnnotation != null && agentAnnotation != null) {
            errors += "Both @Agentic and @Agent annotations found on ${targetType.name}. Treating class as Agent, but both should not be used"
        }
        if (agentAnnotation != null && agentAnnotation.description.isBlank()) {
            errors + "No description provided for @${Agent::class.java.simpleName} on ${targetType.name}"
        }
        return errors
    }

    fun noAutoScan() = embabelComponentAnnotation?.scan == false || agentAnnotation?.scan == false

    /**
     * Name for this agent. Valid only if agentic() is true.
     */
    fun agentName(): String = (agentAnnotation?.name ?: "").ifBlank { targetType.simpleName }

    /**
     * Gets the target class, unwrapping proxies for method discovery.
     */
    fun getTargetType(): Class<*> = targetType
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
    agentStructureValidator: AgentStructureAgentValidator = AgentStructureAgentValidator.PERMIT_ALL,
    pathToCompletionValidator: PathToCompletionAgentValidator = GoapPathToCompletionValidator(),
    private val requireInterfaceDeserializationAnnotations: Boolean = false,
) {

    private val supervisorAgentFactory = SupervisorAgentFactory()

    private val logger = LoggerFactory.getLogger(AgentMetadataReader::class.java)

    private val agentValidationManager: AgentValidationManager = DefaultAgentValidationManager(
        listOf(
            agentStructureValidator,
            pathToCompletionValidator
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
        val targetType = agenticInfo.getTargetType()

        if (!agenticInfo.agentic()) {
            logger.debug(
                "No @{} or @{} annotation found on {}",
                EmbabelComponent::class.simpleName,
                Agent::class.simpleName,
                targetType.name,
            )
            return null
        }

        if (agenticInfo.validationErrors().isNotEmpty()) {
            logger.warn(
                agenticInfo.validationErrors().joinToString("\n"),
                EmbabelComponent::class.simpleName,
                Agent::class.simpleName,
                targetType.name,
            )
            return null
        }
        val getterGoals = findGoalGetters(targetType).map { getGoal(it, instance) }
        val actionMethods = findActionMethods(targetType)
        val conditionMethods = findConditionMethods(targetType)
        val costMethods = findCostMethods(targetType, instance)

        val toolCallbacksOnInstance = safelyGetToolCallbacksFrom(ToolObject.from(instance))

        val conditions = conditionMethods.map { createCondition(it, instance) }.toSet()

        // Collect all actions and goals, including those from @State classes
        val allActions = mutableListOf<CoreAction>()
        val allGoals = mutableListOf<AgentCoreGoal>()
        val processedStateTypes = mutableSetOf<Class<*>>()

        // Process top-level action methods
        for (actionMethod in actionMethods) {
            val action = actionMethodManager.createAction(actionMethod, instance, toolCallbacksOnInstance, costMethods)
            allActions.add(action)
            createGoalFromActionMethod(actionMethod, action, instance)?.let { allGoals.add(it) }

            // Check if this action returns a @State type and unroll it
            val returnType = actionMethod.returnType
            unrollStateType(
                stateType = returnType,
                agentInstance = instance,
                toolCallbacksOnInstance = toolCallbacksOnInstance,
                allActions = allActions,
                allGoals = allGoals,
                processedStateTypes = processedStateTypes,
            )
        }

        val plannerType = agenticInfo.agentAnnotation?.planner ?: PlannerType.GOAP

        val goals = buildSet {
            addAll(getterGoals)
            addAll(allGoals)
            if (plannerType == PlannerType.UTILITY) {
                // Synthetic goal for utility-based agents
                add(NIRVANA)
            }
        }

        if (actionMethods.isEmpty() && goals.isEmpty() && conditionMethods.isEmpty()) {
            logger.warn(
                "❓No methods annotated with @{} or @{} and no goals defined on {}",
                Action::class.simpleName,
                Condition::class.simpleName,
                targetType.name,
            )
            return null
        }

        val agent = if (agenticInfo.agentAnnotation != null) {
            if (plannerType == PlannerType.SUPERVISOR) {
                // Find the goal action (the action with @AchievesGoal)
                val goalActions = actionMethods.filter { it.isAnnotationPresent(AchievesGoal::class.java) }
                if (goalActions.isEmpty()) {
                    logger.warn(
                        "SUPERVISOR planner requires at least one @AchievesGoal action on {}",
                        targetType.name,
                    )
                    return null
                }
                if (goalActions.size > 1) {
                    logger.warn(
                        "SUPERVISOR planner currently supports only one @AchievesGoal action, found {} on {}",
                        goalActions.size,
                        targetType.name,
                    )
                    return null
                }
                val goalAction = allActions.find { action ->
                    goalActions.any { method ->
                        action.name.endsWith(".${method.name}")
                    }
                } ?: error("Goal action not found in allActions")

                supervisorAgentFactory.createSupervisorAgent(
                    agenticInfo = agenticInfo,
                    instance = instance,
                    goalAction = goalAction,
                    allActions = allActions,
                    goals = goals,
                    conditions = conditions,
                )
            } else {
                CoreAgent(
                    name = agenticInfo.agentName(),
                    provider = agenticInfo.agentAnnotation.provider.ifBlank {
                        instance.javaClass.`package`.name
                    },
                    description = agenticInfo.agentAnnotation.description,
                    version = Semver(agenticInfo.agentAnnotation.version),
                    conditions = conditions,
                    actions = allActions,
                    goals = goals,
                    stuckHandler = instance as? StuckHandler,
                    opaque = agenticInfo.agentAnnotation.opaque,
                )
            }
        } else {
            AgentScope(
                name = agenticInfo.type.name,
                conditions = conditions,
                actions = allActions,
                goals = goals,
            )
        }

        // Validate only if an agent, which should be self-contained
        if (plannerType == PlannerType.GOAP && agenticInfo.isAgent()) {
            val validationResult = agentValidationManager.validate(agent)
            if (!validationResult.isValid) {
                logger.warn("Agent validation failed:\n${validationResult.errors.joinToString("\n")}")
                // TODO: Uncomment to strengthen validation and refactor the test if needed. Because some tests might fail.
                // return null
            }
        }

        return agent
    }

    /**
     * Recursively unroll @State types to extract their actions and goals.
     * If the type is a @State class or an interface/sealed class with @State implementations,
     * extract all @Action methods from those state classes and add them to the agent.
     */
    private fun unrollStateType(
        stateType: Class<*>,
        agentInstance: Any,
        toolCallbacksOnInstance: List<ToolCallback>,
        allActions: MutableList<CoreAction>,
        allGoals: MutableList<AgentCoreGoal>,
        processedStateTypes: MutableSet<Class<*>>,
    ) {
        // Find all @State classes to process
        val stateClasses = findStateClasses(stateType)
        for (stateClass in stateClasses) {
            if (processedStateTypes.contains(stateClass)) {
                continue
            }
            processedStateTypes.add(stateClass)
            // Find action methods in the state class
            val stateActionMethods = findActionMethods(stateClass)
            for (actionMethod in stateActionMethods) {
                val action = createActionFromStateMethod(
                    actionMethod,
                    stateClass,
                    agentInstance,
                )
                allActions.add(action)
                createGoalFromStateActionMethod(actionMethod, action, stateClass, agentInstance)?.let {
                    allGoals.add(it)
                }
                // Recursively unroll if this action also returns a @State type
                unrollStateType(
                    stateType = actionMethod.returnType,
                    agentInstance = agentInstance,
                    toolCallbacksOnInstance = toolCallbacksOnInstance,
                    allActions = allActions,
                    allGoals = allGoals,
                    processedStateTypes = processedStateTypes,
                )
            }
        }
    }

    /**
     * Find all @State classes for a given type.
     * If the type itself is annotated with @State, return it.
     * If the type is an interface or sealed class, find all implementations/subclasses
     * that are annotated with @State.
     */
    private fun findStateClasses(type: Class<*>): List<Class<*>> {
        val result = mutableListOf<Class<*>>()
        // Check if the type itself is a @State
        if (type.isAnnotationPresent(State::class.java)) {
            validateStateClass(type)
            result.add(type)
        }
        // Check for subclasses/implementations that are @State
        // This handles sealed classes, interfaces with implementations, and abstract classes
        val jvmType = JvmType(type)
        val children = jvmType.children()
        for (child in children) {
            if (child.clazz.isAnnotationPresent(State::class.java)) {
                validateStateClass(child.clazz)
                result.add(child.clazz)
            }
        }
        return result
    }

    /**
     * Validates a @State class and logs warnings for potential issues.
     * Non-static inner classes (Java) or inner classes (Kotlin) may cause
     * serialization/persistence issues because they hold a reference to their enclosing instance.
     */
    private fun validateStateClass(stateClass: Class<*>) {
        if (stateClass.enclosingClass != null && !java.lang.reflect.Modifier.isStatic(stateClass.modifiers)) {
            logger.warn(
                """
                |
                |========================================================================
                | WARNING: @State class '${stateClass.simpleName}' is a non-static inner class.
                | This may cause serialization/persistence issues because it holds a
                | reference to its enclosing class '${stateClass.enclosingClass.simpleName}'.
                | Consider making it a static nested class (Java) or a top-level class.
                |========================================================================
                """.trimMargin()
            )
        }
    }

    /**
     * Create an action from a method defined in a @State class.
     * The state instance will be created at runtime from the blackboard.
     */
    private fun createActionFromStateMethod(
        method: Method,
        stateClass: Class<*>,
        agentInstance: Any,
    ): CoreAction {
        return StateActionMethodManager(
            actionMethodManager = actionMethodManager,
        ).createAction(method, stateClass, agentInstance)
    }

    /**
     * Create a goal from an @Action method in a @State class that also has @AchievesGoal.
     */
    private fun createGoalFromStateActionMethod(
        method: Method,
        action: CoreAction,
        stateClass: Class<*>,
        agentInstance: Any,
    ): AgentCoreGoal? {
        val actionAnnotation = method.getAnnotation(Action::class.java)
        val goalAnnotation = method.getAnnotation(AchievesGoal::class.java) ?: return null
        val inputBinding = IoBinding(
            name = actionAnnotation.outputBinding,
            type = method.returnType.name,
        )
        // Exclude trigger preconditions from goal - they control when the action fires,
        // not whether the goal was achieved.
        val triggerType = findTriggerType(method)
        val triggerPrecondition = if (triggerType != null) triggerPrecondition(triggerType) else null
        val goalPreconditions = action.preconditions.keys
            .filter { it != triggerPrecondition }
            .toSet()
        return AgentCoreGoal(
            name = "${stateClass.simpleName}.${method.name}",
            description = goalAnnotation.description,
            inputs = setOf(inputBinding),
            outputType = JvmType(method.returnType),
            value = { goalAnnotation.value },
            pre = setOf(Rerun.hasRunCondition(action)) + goalPreconditions,
            export = Export(
                local = goalAnnotation.export.local,
                remote = goalAnnotation.export.remote,
                name = goalAnnotation.export.name.ifBlank { null },
                startingInputTypes = goalAnnotation.export.startingInputTypes.map { it.java }.toSet(),
            )
        )
    }

    private fun findConditionMethods(type: Class<*>): List<Method> {
        val conditionMethods = mutableListOf<Method>()
        ReflectionUtils.doWithMethods(
            type,
            { method -> conditionMethods.add(method) },
            { method ->
                isConditionMethod(method, type)
            })
        return conditionMethods
    }

    /**
     * Find all @Cost methods on the type and return a map of cost method name -> CostMethodInfo.
     */
    private fun findCostMethods(
        type: Class<*>,
        instance: Any,
    ): Map<String, CostMethodInfo> {
        val costMethods = mutableMapOf<String, CostMethodInfo>()
        ReflectionUtils.doWithMethods(
            type,
            { method ->
                val costAnnotation = method.getAnnotation(Cost::class.java)
                val name = costAnnotation.name.ifBlank {
                    nameGenerator.generateName(instance, method.name)
                }
                costMethods[name] = CostMethodInfo(method, instance)
            },
            { method ->
                method.isAnnotationPresent(Cost::class.java) &&
                        (type.declaredMethods.contains(method) || isMethodFromSupertype(method, type))
            })
        return costMethods
    }

    private fun findActionMethods(type: Class<*>): List<Method> {
        val actionMethods = mutableListOf<Method>()
        ReflectionUtils.doWithMethods(
            type,
            { method -> actionMethods.add(method) },
            // Get annotated methods from this type and interfaces
            { method -> isActionMethod(method, type) })
        if (actionMethods.isEmpty()) {
            logger.debug("No methods annotated with @{} found in {}", Action::class.simpleName, type)
        }
        return actionMethods
    }

    private fun isActionMethod(
        method: Method,
        type: Class<*>,
    ): Boolean {
        return method.isAnnotationPresent(Action::class.java) &&
                (type.declaredMethods.contains(method) || isMethodFromSupertype(method, type)) &&
                (!method.returnType.isInterface || !requireInterfaceDeserializationAnnotations || hasRequiredJsonDeserializeAnnotationOnInterfaceReturnType(
                    method
                ))
    }

    private fun isConditionMethod(
        method: Method,
        type: Class<*>,
    ): Boolean {
        return method.isAnnotationPresent(Condition::class.java) &&
                (type.declaredMethods.contains(method) || isMethodFromSupertype(method, type))
    }

    private fun isMethodFromSupertype(
        method: Method,
        type: Class<*>,
    ): Boolean {
        // Check interfaces
        if (type.interfaces.any { interfaceType ->
                interfaceType.declaredMethods.any { interfaceMethod ->
                    methodSignaturesMatch(method, interfaceMethod)
                }
            }) {
            return true
        }

        // Check superclasses
        var superclass = type.superclass
        while (superclass != null && superclass != Any::class.java) {
            if (superclass.declaredMethods.any { superMethod ->
                    methodSignaturesMatch(method, superMethod)
                }) {
                return true
            }
            superclass = superclass.superclass
        }

        return false
    }

    private fun methodSignaturesMatch(
        method1: Method,
        method2: Method,
    ): Boolean {
        return method1.name == method2.name &&
                method1.parameterTypes.contentEquals(method2.parameterTypes) &&
                method1.returnType == method2.returnType
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
        requireNonAmbiguousParameters(method)
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
                    val domainTypes = context.agentProcess.agent.jvmTypes.map { it.clazz }
                    val variable = getBindingParameterName(parameter.name, requireNameMatch)
                        ?: error("Parameter name should be available")
                    args += context.getValue(
                        variable = variable,
                        type = parameter.type.name,
                        context.agentProcess.agent,
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
            method.trySetAccessible()
            val evaluationResult = ReflectionUtils.invokeMethod(method, instance, *args.toTypedArray()) as Boolean
            logger.debug(
                "Condition evaluated to {}, calling {} on {} using args {}",
                evaluationResult,
                method.name,
                instance.javaClass.name,
                args,
            )
            evaluationResult
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
        // Exclude trigger preconditions from goal - they control when the action fires,
        // not whether the goal was achieved. After the action runs, the lastResult changes
        // and the trigger condition becomes false, which should not prevent goal completion.
        val triggerType = findTriggerType(method)
        val triggerPrecondition = if (triggerType != null) triggerPrecondition(triggerType) else null
        val goalPreconditions = action.preconditions.keys
            .filter { it != triggerPrecondition }
            .toSet()
        return AgentCoreGoal(
            name = nameGenerator.generateName(instance, method.name),
            description = goalAnnotation.description,
            inputs = setOf(inputBinding),
            outputType = JvmType(method.returnType),
            value = { goalAnnotation.value },
            // Add precondition of the action having run
            pre = setOf(Rerun.hasRunCondition(action)) + goalPreconditions,
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
    val hasRequiredAnnotation = method.returnType.isAnnotationPresent(JsonDeserialize::class.java) ||
            method.returnType.isAnnotationPresent(JsonTypeInfo::class.java)
    if (!hasRequiredAnnotation) {
        loggerFor<AgentMetadataReader>().warn(
            "❓Interface {} used as return type of {}.{} must have @JsonDeserialize or @JsonTypeInfo annotation",
            method.returnType.name,
            method.declaringClass.name,
            method.name,
        )
    }
    return hasRequiredAnnotation
}
