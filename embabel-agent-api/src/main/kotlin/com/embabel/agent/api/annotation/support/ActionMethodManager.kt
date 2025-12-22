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

import com.embabel.agent.api.annotation.State
import com.embabel.agent.api.annotation.Action as ActionAnnotation
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.core.Action
import com.embabel.agent.core.IoBinding
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
 * Respects inheritance - returns true if the class itself or any of its
 * superclasses has the @State annotation.
 */
internal fun isStateType(clazz: Class<*>): Boolean {
    var current: Class<*>? = clazz
    while (current != null && current != Any::class.java) {
        if (current.isAnnotationPresent(State::class.java)) {
            return true
        }
        current = current.superclass
    }
    return false
}
