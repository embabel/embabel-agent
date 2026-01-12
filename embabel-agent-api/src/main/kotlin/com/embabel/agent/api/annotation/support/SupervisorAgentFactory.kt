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

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.*
import com.embabel.agent.core.support.AbstractAction
import com.embabel.agent.core.support.Rerun
import com.embabel.agent.spi.LlmInteraction
import com.embabel.common.core.types.Semver
import org.slf4j.LoggerFactory
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Factory for creating supervisor agents.
 *
 * A supervisor agent uses an LLM to orchestrate actions (exposed as tools)
 * to achieve a goal. This implements a pattern similar to LangGraph's supervisor.
 *
 * @see <a href="https://langchain-ai.github.io/langgraph/concepts/agentic_concepts/#supervisor">LangGraph Supervisor</a>
 */
internal class SupervisorAgentFactory {

    private val logger = LoggerFactory.getLogger(SupervisorAgentFactory::class.java)

    /**
     * Create an agent using the supervisor pattern, where an LLM acts as a supervisor
     * that orchestrates actions and @Tool methods to achieve a goal.
     *
     * @param agenticInfo Information about the agent class
     * @param instance The agent instance
     * @param goalAction The action that achieves the goal (has @AchievesGoal)
     * @param allActions All actions defined on the agent
     * @param goals All goals defined on the agent
     * @param conditions All conditions defined on the agent
     */
    fun createSupervisorAgent(
        agenticInfo: AgenticInfo,
        instance: Any,
        goalAction: Action,
        allActions: List<Action>,
        goals: Set<Goal>,
        conditions: Set<Condition>,
    ): CoreAgent {
        // Get non-goal actions to be exposed as tools
        val toolActions = allActions.filter { it.name != goalAction.name }

        logger.info(
            "Creating supervisor agent '{}' with {} tool actions and goal action '{}'",
            agenticInfo.agentName(),
            toolActions.size,
            goalAction.shortName(),
        )

        // Create the single supervisor action that orchestrates everything
        val supervisorAction = createSupervisorAction(
            agentName = agenticInfo.agentName(),
            goalAction = goalAction,
            toolActions = toolActions,
        )

        // Update goals to reference the supervisor action instead of original goal action
        val updatedGoals = goals.map { goal ->
            updateGoalForSupervisor(goal, supervisorAction)
        }.toSet()

        return CoreAgent(
            name = agenticInfo.agentName(),
            provider = agenticInfo.agentAnnotation?.provider?.ifBlank {
                instance.javaClass.`package`.name
            } ?: instance.javaClass.`package`.name,
            description = agenticInfo.agentAnnotation?.description ?: "",
            version = Semver(agenticInfo.agentAnnotation?.version ?: "0.1.0"),
            conditions = conditions,
            actions = listOf(supervisorAction),
            goals = updatedGoals,
            opaque = agenticInfo.agentAnnotation?.opaque ?: false,
        )
    }

    /**
     * Update a goal's preconditions to reference the supervisor action.
     * For supervisor agents, the goal only needs:
     * - The supervisor action's hasRun condition
     * - The output type on the blackboard (from supervisor action's outputs)
     *
     * All intermediate preconditions (like Frog from intermediate actions) are removed
     * since the supervisor handles the entire workflow internally.
     */
    private fun updateGoalForSupervisor(goal: Goal, supervisorAction: Action): Goal {
        // Use only the supervisor action's hasRun condition
        // The output type precondition will be derived from goal.inputs automatically
        val updatedPre = setOf(Rerun.hasRunCondition(supervisorAction))

        // Update inputs to match the supervisor action's outputs (the final result type)
        val updatedInputs = supervisorAction.outputs

        return Goal(
            name = goal.name,
            description = goal.description,
            inputs = updatedInputs,
            outputType = goal.outputType,
            value = goal.value,
            pre = updatedPre,
            export = goal.export,
        )
    }

    /**
     * Create the supervisor action that uses LLM to orchestrate tool actions.
     */
    private fun createSupervisorAction(
        agentName: String,
        goalAction: Action,
        toolActions: List<Action>,
    ): Action {
        // Compute inputs: types needed by any action that aren't produced by any action
        // These are "source" types that must come from the user
        val allActions = toolActions + goalAction
        val allOutputTypes = allActions.flatMap { it.outputs }.map { it.type }.toSet()

        // Gather inputs from tool actions (not goal - goal inputs may be intermediate types)
        // plus any goal inputs that aren't intermediate types
        val toolInputs = toolActions.flatMap { it.inputs }
        val goalInputsNotProduced = goalAction.inputs.filter { it.type !in allOutputTypes }
        val inputs = (toolInputs + goalInputsNotProduced)
            .filter { input -> input.type !in allOutputTypes }
            .distinctBy { it.type } // Dedupe by type
            .toSet()

        val outputs = goalAction.outputs

        logger.debug(
            "Supervisor action inputs: {}, outputs: {}",
            inputs.map { it.type },
            outputs.map { it.type },
        )

        return SupervisorAction(
            name = "$agentName.supervisor",
            description = "Supervisor action that orchestrates tools to achieve the goal",
            inputs = inputs,
            outputs = outputs,
            toolActions = toolActions,
            goalAction = goalAction,
        )
    }
}

/**
 * The supervisor action that uses an LLM to orchestrate tool actions.
 *
 * This action dynamically creates curried tools based on the current blackboard state.
 * Actions whose inputs are already available on the blackboard will have those
 * parameters "curried out", simplifying the tool interface for the LLM.
 *
 * @param toolActions The actions to expose as tools (excluding the goal action)
 * @param goalAction The final action that achieves the goal
 */
class SupervisorAction(
    name: String,
    description: String,
    inputs: Set<IoBinding>,
    outputs: Set<IoBinding>,
    val toolActions: List<Action>,
    private val goalAction: Action,
) : AbstractAction(
    name = name,
    description = description,
    pre = emptyList(),
    post = emptyList(),
    cost = { 0.0 },
    value = { 0.0 },
    inputs = inputs,
    outputs = outputs,
    toolGroups = emptySet(),
    canRerun = false,
    qos = ActionQos(),
) {

    override fun execute(processContext: ProcessContext): ActionStatus =
        ActionRunner.execute(processContext) {
            val objectMapper = processContext.platformServices.objectMapper
            val llmOperations = processContext.platformServices.llmOperations
            val goalOutputType = goalAction.outputs.firstOrNull()?.type

            var iteration = 0
            val maxIterations = 10 // Safety limit

            // Agentic loop: regenerate curried tools after each tool execution
            while (iteration < maxIterations) {
                iteration++

                // Check if goal is already achieved
                if (isGoalAchieved(processContext, goalOutputType)) {
                    logger.info("Goal achieved after {} iterations", iteration - 1)
                    break
                }

                // Create curried tools based on CURRENT blackboard state
                val curriedTools = CurriedActionTool.createTools(
                    actions = toolActions,
                    blackboard = processContext.blackboard,
                    objectMapper = objectMapper,
                )

                logger.info(
                    "Supervisor iteration {}: {} curried tools (from {} actions)",
                    iteration,
                    curriedTools.size,
                    toolActions.size
                )

                // Log which tools are ready vs need inputs
                curriedTools.forEach { tool ->
                    val isReady = CurriedActionTool.isReady(tool)
                    logger.debug(
                        "Tool '{}': {} - {}",
                        tool.definition.name,
                        if (isReady) "READY" else "needs inputs",
                        tool.definition.description
                    )
                }

                // Build prompt with current state and updated tools
                val prompt = buildSupervisorPrompt(processContext, curriedTools, iteration)

                // Create interaction with tools
                val interaction = LlmInteraction(
                    id = InteractionId("$name-supervisor-$iteration"),
                    tools = curriedTools,
                )

                // Execute with tools
                val response = llmOperations.generate(
                    prompt = prompt,
                    interaction = interaction,
                    agentProcess = processContext.agentProcess,
                    action = this,
                )

                logger.info("Supervisor iteration {} response: {}", iteration, response)

                // Check if goal is achieved after this iteration
                if (isGoalAchieved(processContext, goalOutputType)) {
                    logger.info("Goal achieved after iteration {}", iteration)
                    break
                }
            }

            if (iteration >= maxIterations) {
                logger.warn("Supervisor reached max iterations ({}) without achieving goal", maxIterations)
            }

            // Check if the goal action can now be executed (all inputs available)
            if (!isGoalAchieved(processContext, goalOutputType) && canExecuteGoalAction(processContext)) {
                logger.info("Goal action inputs available, executing goal action: {}", goalAction.shortName())
                executeGoalAction(processContext)
            }

            // Bind goal output to expected output binding
            if (goalOutputType != null) {
                val blackboardModel = processContext.blackboard.expressionEvaluationModel()
                val goalOutput = blackboardModel.values.find { value ->
                    value::class.java.name == goalOutputType
                }
                if (goalOutput != null) {
                    logger.info("Goal output found on blackboard: {}", goalOutput)
                    val outputBinding = outputs.firstOrNull()?.name ?: IoBinding.DEFAULT_BINDING
                    processContext.blackboard[outputBinding] = goalOutput
                } else {
                    logger.warn("Goal output type {} not found on blackboard after orchestration", goalOutputType)
                }
            }
        }

    private fun isGoalAchieved(processContext: ProcessContext, goalOutputType: String?): Boolean {
        if (goalOutputType == null) return false
        val blackboardModel = processContext.blackboard.expressionEvaluationModel()
        return blackboardModel.values.any { value ->
            value::class.java.name == goalOutputType
        }
    }

    private fun canExecuteGoalAction(processContext: ProcessContext): Boolean {
        // Check both map values and objects list for available inputs
        val blackboard = processContext.blackboard
        val mapValues = blackboard.expressionEvaluationModel().values.filterNotNull()
        val objectValues = blackboard.objects
        val allValues = (mapValues + objectValues).distinct()

        // Check if all inputs for the goal action are available on the blackboard
        return goalAction.inputs.all { input ->
            allValues.any { value ->
                CurriedActionTool.isCompatibleType(value, input.type)
            }
        }
    }

    private fun executeGoalAction(processContext: ProcessContext) {
        val result = goalAction.execute(processContext)
        logger.info("Goal action '{}' executed with result: {}", goalAction.shortName(), result)
    }

    private fun buildSupervisorPrompt(
        processContext: ProcessContext,
        tools: List<Tool>,
        iteration: Int,
    ): String {
        val objectMapper = processContext.platformServices.objectMapper

        // Build action signatures with type schemas
        val actionSignatures = toolActions.joinToString("\n") { action ->
            "- " + TypeSchemaExtractor.buildActionSignature(action)
        }

        // Build artifacts summary showing typed values on blackboard
        val artifactsSummary = TypeSchemaExtractor.buildArtifactsSummary(
            blackboard = processContext.blackboard,
            objectMapper = objectMapper,
        )

        // Goal type info
        val goalTypeName = goalAction.outputs.firstOrNull()?.type?.substringAfterLast(".") ?: "goal"
        val goalSchema = goalAction.outputs.firstOrNull()?.let {
            TypeSchemaExtractor.extractSchema(it.type)
        } ?: ""

        val iterationNote = if (iteration > 1) {
            "\n\nIteration $iteration: Review the artifacts gathered so far and decide the next step."
        } else {
            ""
        }

        return """
            |You are a supervisor agent that orchestrates actions to achieve a goal.
            |You decide what to call and when, based on the available actions and gathered artifacts.
            |
            |## Available Actions
            |Each action shows its signature and output type schema:
            |$actionSignatures
            |
            |## Current Artifacts
            |Data gathered so far (typed and validated):
            |$artifactsSummary
            |
            |## Goal
            |Produce: $goalTypeName
            |Schema: $goalSchema
            |Description: ${goalAction.description}
            |$iterationNote
            |
            |## Instructions
            |- Review the available actions and their output types
            |- Consider what artifacts you've gathered and what you still need
            |- Call ONE action to make progress toward the goal
            |- You can pass any appropriate arguments - use gathered data as context
            |- When you have enough information, call the goal action to produce the final result
            |- The action outputs are TYPED - you'll see their schemas above
        """.trimMargin()
    }

    override fun referencedInputProperties(variable: String): Set<String> = emptySet()
}
