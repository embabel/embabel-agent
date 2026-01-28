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
package com.embabel.agent.api.annotation

import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.IoBinding
import com.embabel.agent.core.ActionRetryPolicy
import com.embabel.common.core.types.Semver.Companion.DEFAULT_VERSION
import com.embabel.common.core.types.ZeroToOne
import org.springframework.core.annotation.AliasFor
import org.springframework.stereotype.Component
import kotlin.reflect.KClass


/**
 * Indicates that this class exposes actions, goals and conditions that may be used
 * by agents, but is not an agent in itself.
 * This is a Spring stereotype annotation, so annotated classes will be picked up on the classpath and injected
 * @param scan Whether to find this agent in the classpath. If false, it will not be found by classpath scanning.
 * This is useful for testing
 * [com.embabel.agent.api.annotation.support.AgentMetadataReader] will still process it if asked directly.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS,
)
@Component
annotation class EmbabelComponent(
    val scan: Boolean = true,
)

/**
 * Indicates that this class is an agent.
 * It doesn't just contribute actions, goals and conditions:
 * it is an agent in itself.
 * This is a Spring stereotype annotation, so annotated classes will be picked up on the classpath and injected
 * Either @Agent or @AgentCapabilities should be used: not both
 * @param name Name of the agent. If not provided, the name will be the class simple name
 * @param provider provider of the agent. If not provided, will default to the package this annotation is used in
 * @param description Description of the agent. Required. This is used for documentation purposes and to choose an agent
 * @param version Version of the agent
 * @param planner The type of planning this agent uses. Defaults to GOAP (Goal Oriented Action Planning).
 * @param scan Whether to find this agent in the classpath. If false, it will not be found by the agent manager. Defaults to true
 * @param beanName The value may indicate a suggestion for a logical component name,
 * to be turned into a Spring bean in case of an autodetected component. Use only if there's the likelihood of
 * conflict with the default bean name.
 * @param opaque Whether to hide the agent's actions and conditions
 * @param actionRetryPolicy {@link com.embabel.agent.core.ActionRetryPolicy} for how to manage retries per action.
 * Use actionRetryPolicyExpression to specify specific properties. You can override this per action in the {@link Action} annotation.
 * @param actionRetryPolicyExpression An expression pointing to a set of properties for how to manage retries per action
 * overriding these (these are the defaults if you do not specify):
 *   max-attempts: int = 5
 *   backoff-millis: long = 10000
 *   backoff-multiplier: double = 5.0
 *   backoff-maxInterval: long = 60000
 *   idempotent: boolean = false
 * example: ${agent.action-retry.default}
 * You can override this per action in the {@link Action} annotation.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(
    AnnotationTarget.CLASS,
)
@Component
annotation class Agent(
    val name: String = "",
    val provider: String = "",
    val description: String,
    val version: String = DEFAULT_VERSION,
    val planner: PlannerType = PlannerType.GOAP,
    val scan: Boolean = true,
    @get:AliasFor(annotation = Component::class, attribute = "value")
    val beanName: String = "",
    val opaque: Boolean = false,
    val actionRetryPolicy: ActionRetryPolicy = ActionRetryPolicy.DEFAULT,
    val actionRetryPolicyExpression: String = "",
)

/**
 * Annotates a method that evaluates a condition.
 * This will have access to the processContext and also
 * can use any other state.
 * @param name Name of the condition. If not provided, the name will be the method name
 * Useful if we want to avoid magic strings by sharing a constant
 * @param cost Cost of evaluating the condition, between 0 and 1.
 * 0 is cheap; 1 is the most expensive. The platform can use this
 * information for optimization.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Condition(
    val name: String = "",
    val cost: ZeroToOne = 0.0,
)

/**
 * Annotates a method that computes the dynamic cost or value of an action at planning time.
 * Similar to @Condition, this method can take domain object parameters from the blackboard.
 * **Unlike @Condition, all domain object parameters must be nullable.**
 * If a parameter is not available on the blackboard, null will be passed.
 *
 * The method can also take a `Blackboard` parameter for direct access to all available objects.
 *
 * The method must return a Double between 0.0 and 1.0.
 *
 * Example:
 * ```java
 * @Cost(name = "processingCost")
 * public double computeProcessingCost(@Nullable LargeDataSet largeData) {
 *     return largeData != null ? 0.9 : 0.1;
 * }
 *
 * @Action(costMethod = "processingCost")
 * public DataOutput processData(DataInput input) { ... }
 * ```
 *
 * @param name Name of the cost method. Referenced by @Action.costMethod or @Action.valueMethod.
 * If not provided, the name will be the method name.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Cost(
    val name: String = "",
)

@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ToolGroup(
    val role: String,
)

/**
 * Annotation to indicate a method implementing an Action.
 * Methods can have any number of parameters, which represent
 * necessary input types.
 * Methods can return any type. The return type will become
 * an effect.
 * @param description Description of the action. Less important than for
 * goals as a planner chooses actions based on preconditions
 * and effects rather than by description. The description property is
 * used for documentation purposes, having the advantage over comments
 * that it can appear in logs. Description defaults to name
 * @param pre Preconditions for the action
 * @param post Postconditions for the action
 * @param canRerun can we rerun this action?
 * If false, the action will not be rerun if it has already run in the current process
 * @param clearBlackboard If true, all previous state will be cleared from the blackboard,
 * leaving only the outputs of this action.
 * @param outputBinding Output binding for the action.
 * Only required for a custom binding: a specific variable name for the returned value.
 * @param cost Static cost of executing the action. Ignored if [costMethod] is specified.
 * @param value Static value of performing the action. Ignored if [valueMethod] is specified.
 * @param costMethod Name of a @Cost method to compute dynamic cost at planning time.
 * When specified, overrides the static [cost] field.
 * @param valueMethod Name of a @Cost method to compute dynamic value at planning time.
 * When specified, overrides the static [value] field.
 * @Tool methods on the @Agentic class are automatically added.
 * @param trigger The type that must be the last result on the blackboard for this action to fire.
 * This enables reactive behavior where an action only fires when a specific type
 * is freshly added, even when multiple parameters of various types are available.
 * Defaults to Unit::class (no trigger). A trigger is an **additional** precondition: it
 * must be satisfied in addition to any preconditions listed in [pre] and the action method's input parameters.
 * @param actionRetryPolicy {@link com.embabel.agent.core.ActionRetryPolicy} for how to manage retries for this action.
 * Use actionRetryPolicyExpression
 * to specify specific properties.
 * @param actionRetryPolicyExpression An expression pointing to a set of properties for how to manage retries for this
 * action overriding these (these are the defaults if you do not specify):
 *   max-attempts: int = 5
 *   backoff-millis: long = 10000
 *   backoff-multiplier: double = 5.0
 *   backoff-maxInterval: long = 60000
 *   idempotent: boolean = false
 * example: ${agent.action-retry.default}
 * These take precedence over specifying the default in the Agent annotation.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class Action(
    val description: String = "",
    val pre: Array<String> = [],
    val post: Array<String> = [],
    val canRerun: Boolean = false,
    val clearBlackboard: Boolean = false,
    val outputBinding: String = IoBinding.DEFAULT_BINDING,
    val cost: ZeroToOne = 0.0,
    val value: ZeroToOne = 0.0,
    val costMethod: String = "",
    val valueMethod: String = "",
    val trigger: KClass<*> = Unit::class,
    val actionRetryPolicy: ActionRetryPolicy = ActionRetryPolicy.DEFAULT,
    val actionRetryPolicyExpression: String = "",
)

/**
 * Marks a class representing a state within a flow.
 * States do not trigger subflows but hold a subset of actions.
 * Returning a State from an action indicates a transition to that state
 * and creates the effects of the state's ultimate goals.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class State


/**
 * Annotation that can added to parameters of an @Action method
 * to indicate that the parameter name must match the input binding.
 * Otherwise, it can match the latest ("it") value.
 * Must be combined with the outputBinding method on Action for the action
 * producing the input
 * @param value The name of the input binding that this parameter should match; "" indicates using the parameter name.
 * @see Action
 * @see IoBinding
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RequireNameMatch(
    val value: String = "",
)
