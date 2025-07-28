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
package com.embabel.agent.api.dsl

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.Transformation
import com.embabel.agent.api.common.TransformationActionContext
import com.embabel.agent.api.common.asAction
import com.embabel.agent.api.common.support.TransformationAction
import com.embabel.agent.api.dsl.support.promptTransformer
import com.embabel.agent.core.*
import com.embabel.agent.experimental.primitive.PromptCondition
import com.embabel.agent.spi.LlmCall
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.ai.prompt.PromptContributorConsumer
import com.embabel.common.core.types.Semver
import com.embabel.common.core.types.ZeroToOne
import com.embabel.plan.goap.ConditionDetermination
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallback
import kotlin.reflect.KClass
import kotlin.reflect.KProperty


typealias ConditionPredicate = (
    context: OperationContext,
) -> Boolean?

/**
 * Instances of this are usually created via the convenient agent() function.
 */
class AgentBuilder(
    val name: String,
    val provider: String = "embabel",
    val version: Semver = Semver(),
    val description: String,
    promptContributors: List<PromptContributor> = emptyList(),
) : PromptContributorConsumer {

    private val logger = LoggerFactory.getLogger(AgentBuilder::class.java)

    // Needs to be public for inline functions to work
    val actions = mutableListOf<Action>()

    private val goals = mutableSetOf<Goal>()

    private val conditions = mutableSetOf<Condition>()

    private val _promptContributors = promptContributors.toMutableList()

    override val promptContributors: List<PromptContributor>
        get() = _promptContributors

    /**
     * Create an action
     */
    fun action(block: AgentBuilder.() -> Action) {
        actions.add(block())
    }

    /**
     * Require prompt contributors at agent level
     */
    fun registerPromptContributors(vararg promptContributors: PromptContributor) {
        _promptContributors += promptContributors
    }

    /**
     * Add an action is an anonymous agent. This is valuable because the
     * agent will be private and will not pollute the global action space.
     */
    inline fun <reified I, reified O : Any> localAgentAction(agent: Agent) {
        val aa = agent.asAction<I, O>()
        actions.add(aa)
    }

    /**
     * Add an action that references the agent with a given name
     */
    inline fun <reified I, reified O : Any> referencedAgentAction(agentName: String) {
        val aa = asAction<I, O>(agentName)
        actions.add(aa)
    }

    fun add(agentScope: AgentScope) {
        actions += agentScope.actions
        goals += agentScope.goals
        conditions += agentScope.conditions
    }

    /**
     * Add a pattern builder, such as an aggregation flow, to the agent.
     * Must be used if you have multiple actions
     */
    fun flow(block: AgentBuilder.() -> AgentScopeBuilder<*>) {
        val agentScope = block().build()
        logger.info(
            "Adding actions from agent scope {}: {}",
            agentScope.name,
            agentScope.actions,
        )
        add(agentScope)
    }

    /**
     * Add an action that is a transformation NOT using an LLM.
     */
    inline fun <reified I, reified O : Any> transformation(
        name: String = "${I::class.simpleName}-${O::class.simpleName}",
        description: String = name,
        preConditions: List<Condition> = emptyList(),
        pre: List<String> = emptyList(),
        post: List<Condition> = emptyList(),
        inputVarName: String = IoBinding.DEFAULT_BINDING,
        outputVarName: String? = IoBinding.DEFAULT_BINDING,
        cost: ZeroToOne = 0.0,
        toolGroups: Set<ToolGroupRequirement> = emptySet(),
        qos: ActionQos = ActionQos(),
        referencedInputProperties: Set<String>? = null,
        block: Transformation<I, O>,
    ) {
        val action = TransformationAction(
            name = name,
            description = description,
            pre = pre + preConditions.map { it.name },
            post = post.map { it.name },
            cost = cost,
            qos = qos,
            inputVarName = inputVarName,
            outputVarName = outputVarName,
            inputClass = I::class.java,
            outputClass = O::class.java,
            referencedInputProperties = referencedInputProperties,
            toolGroups = toolGroups,
            block = block,
        )
        actions.add(action)
    }

    /**
     * Add an action that is a transformation using an LLM.
     */
    inline fun <reified I, reified O : Any> promptedTransformer(
        name: String,
        description: String = name,
        pre: List<Condition> = emptyList(),
        post: List<Condition> = emptyList(),
        inputVarName: String = IoBinding.DEFAULT_BINDING,
        outputVarName: String = IoBinding.DEFAULT_BINDING,
        cost: ZeroToOne = 0.0,
        toolGroups: Set<ToolGroupRequirement> = emptySet(),
        qos: ActionQos = ActionQos(),
        referencedInputProperties: Set<String>? = null,
        llm: LlmOptions = LlmOptions(),
        promptContributors: List<PromptContributor> = emptyList(),
        canRerun: Boolean = false,
        toolCallbacks: Collection<ToolCallback> = emptyList(),
        noinline prompt: (actionContext: TransformationActionContext<I, O>) -> String,
    ) {
        val action = promptTransformer(
            name = name,
            description = description,
            pre = pre,
            post = post,
            inputVarName = inputVarName,
            outputVarName = outputVarName,
            cost = cost,
            toolGroups = toolGroups,
            qos = qos,
            referencedInputProperties = referencedInputProperties,
            llm = llm,
            canRerun = canRerun,
            toolCallbacks = toolCallbacks,
            prompt = prompt,
            promptContributors = this.promptContributors + promptContributors,
            inputClass = I::class.java,
            outputClass = O::class.java,
        )
        actions.add(action)
    }

    /**
     * Add a goal to the agent.
     * @param name The name of the goal.
     * @param description A description of the goal. Should be informative,
     * to allow the platform to choose a goal based on user input.
     * @param satisfiedBy A class that satisfies this goal.
     * @param requires A set of classes that are required to satisfy this goal.
     * @param pre custom preconditions, in addition to input preconditions
     * @param value the value of achieving this goal
     */
    fun goal(
        name: String,
        description: String,
        satisfiedBy: KClass<*>? = null,
        requires: Set<KClass<*>> = if (satisfiedBy != null) {
            setOf(satisfiedBy)
        } else {
            emptySet()
        },
        inputs: Set<IoBinding> = requires.map {
            IoBinding(
//                name = it.simpleName,
                type = it,
            )
        }.toSet(),
        pre: List<Condition> = emptyList(),
        value: ZeroToOne = 0.0,
        startingInputTypes: Set<KClass<*>> = emptySet(),
        export: Export = Export(),
    ) {
        // TODO check validity
        goals.add(
            Goal(
                name = name,
                description = description,
                inputs = inputs,
                pre = pre.map { it.name }.toSet(),
                value = value,
                startingInputTypes = startingInputTypes.map { it.java }.toSet(),
                export = export,
            )
        )
    }

    fun condition(block: AgentBuilder.() -> Condition) {
        conditions.add(block())
    }

    class ConditionDelegateProvider(
        private val specifiedName: String? = null,
        private val factory: (name: String) -> Condition,
        private val conditions: MutableSet<Condition>
    ) {
        operator fun provideDelegate(thisRef: Any?, prop: KProperty<*>): ConditionDelegate {
            val condition = factory(specifiedName ?: prop.name)
            conditions.add(condition) // Store the condition
            return ConditionDelegate(condition)
        }
    }

    class ConditionDelegate(
        private val condition: Condition
    ) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Condition = condition
    }

    fun condition(
        name: String? = null,
        cost: ZeroToOne = 0.0,
        block: ConditionPredicate,
    ): ConditionDelegateProvider {
        return ConditionDelegateProvider(
            name,
            {
                val condition = object : Condition {
                    override val name = it
                    override val cost = cost
                    override fun evaluate(context: OperationContext): ConditionDetermination =
                        ConditionDetermination(
                            block(
                                context
                            )
                        )
                }
                condition
            },
            this.conditions
        )
    }

    /**
     * Declare a condition determined with an LLM. Assign it a name to ensure
     * type safe access
     * val myCondition = condition("custom prompt")
     */
    fun condition(
        name: String? = null,
        prompt: (context: OperationContext) -> String,
        llm: LlmCall = LlmCall(),
    ): ConditionDelegateProvider {
        return ConditionDelegateProvider(
            specifiedName = name,
            factory = {
                PromptCondition(
                    name = it,
                    prompt = prompt,
                    llm = llm,
                )
            },
            conditions = this.conditions,
        )
    }

    /**
     * Build the agent
     */
    fun build(): Agent {
        return Agent(
            name = name,
            provider = provider,
            version = version,
            description = description,
            conditions = conditions,
            actions = actions,
            goals = goals,
        )
    }

}
