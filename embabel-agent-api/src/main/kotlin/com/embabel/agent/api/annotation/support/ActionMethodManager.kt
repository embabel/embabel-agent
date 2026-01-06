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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.State
import com.embabel.agent.api.annotation.Action as ActionAnnotation
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.core.Action
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ToolGroupRequirement
import org.springframework.ai.tool.ToolCallback
import java.lang.reflect.Method

/**
 * Information about a @Cost annotated method for invoking cost/value computations.
 */
data class CostMethodInfo(
    val method: Method,
    val instance: Any,
)

/**
 * Creates and invokes actions from annotated methods.
 */
interface ActionMethodManager {

    /**
     * Strategies for resolving action method parameters into argument values
     * Handles core types such as Ai and OperationContext, but can be
     * extended to support custom parameter types.
     */
    val argumentResolvers: List<ActionMethodArgumentResolver>

    /**
     * Create an Action from a method
     * @param method the method to create an action from
     * @param instance instance of Agent or AgentCapabilities-annotated class
     * @param toolCallbacksOnInstance tool callbacks to use from instance level
     * @param costMethods map of cost method name to CostMethodInfo for dynamic cost/value computation
     */
    fun createAction(
        method: Method,
        instance: Any,
        toolCallbacksOnInstance: List<ToolCallback>,
        costMethods: Map<String, CostMethodInfo> = emptyMap(),
    ): Action

    /**
     * Invoke the action method on the given instance.
     */
    fun <O> invokeActionMethod(
        method: Method,
        instance: Any,
        actionContext: TransformationActionContext<List<Any>, O>,
    ): O
}

/**
 * Find the trigger type for an action method from the @Action annotation's trigger field.
 * Shared between DefaultActionMethodManager and StateActionMethodManager.
 */
internal fun findTriggerType(method: Method): Class<*>? {
    val actionAnnotation = method.getAnnotation(ActionAnnotation::class.java)
    if (actionAnnotation != null && actionAnnotation.trigger != Unit::class) {
        return actionAnnotation.trigger.java
    }
    return null
}

/**
 * Generate the data binding precondition for a @Trigger parameter type.
 * Uses the standard binding format "lastResult:fully.qualified.Type" which is
 * evaluated by BlackboardWorldStateDeterminer.
 */
internal fun triggerPrecondition(triggerType: Class<*>): String =
    "${IoBinding.LAST_RESULT_BINDING}:${triggerType.name}"

/**
 * Check if a class is a @State type.
 * Respects inheritance - returns true if the class itself, any of its
 * superclasses, or any implemented interfaces has the @State annotation.
 */
internal fun isStateType(clazz: Class<*>): Boolean {
    val visited = mutableSetOf<Class<*>>()
    return isStateTypeRecursive(clazz, visited)
}

private fun isStateTypeRecursive(clazz: Class<*>?, visited: MutableSet<Class<*>>): Boolean {
    if (clazz == null || clazz == Any::class.java || !visited.add(clazz)) {
        return false
    }
    if (clazz.isAnnotationPresent(State::class.java)) {
        return true
    }
    // Check superclass
    if (isStateTypeRecursive(clazz.superclass, visited)) {
        return true
    }
    // Check all interfaces
    for (iface in clazz.interfaces) {
        if (isStateTypeRecursive(iface, visited)) {
            return true
        }
    }
    return false
}

/**
 * Compute whether an action should clear the blackboard.
 * Returns true if the method returns a @State type or if explicitly set in annotation.
 */
internal fun computeClearBlackboard(method: Method, actionAnnotation: ActionAnnotation): Boolean =
    isStateType(method.returnType) || actionAnnotation.clearBlackboard

/**
 * Compute trigger preconditions for an action method.
 * Returns a list containing the trigger precondition if @Action.trigger is set.
 */
internal fun computeTriggerPreconditions(method: Method): List<String> {
    val triggerType = findTriggerType(method)
    return if (triggerType != null) {
        listOf(triggerPrecondition(triggerType))
    } else {
        emptyList()
    }
}

/**
 * Compute tool group requirements from an @Action annotation.
 */
internal fun computeToolGroups(actionAnnotation: ActionAnnotation): Set<ToolGroupRequirement> =
    (actionAnnotation.toolGroupRequirements.map { ToolGroupRequirement(it.role) } +
            actionAnnotation.toolGroups.map { ToolGroupRequirement(it) }).toSet()
