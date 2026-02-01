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
package com.embabel.agent.api.tool

import com.embabel.agent.api.annotation.support.AgenticInfo
import com.embabel.agent.api.common.autonomy.ProcessWaitingException
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import com.embabel.agent.tools.agent.PromptedTextCommunicator
import com.embabel.agent.tools.agent.TextCommunicator
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * A [Tool] that delegates to another agent as a subagent/handoff.
 *
 * When the LLM invokes this tool, it runs the specified agent as a subprocess,
 * sharing the parent process's blackboard context. This enables composition of agents
 * and "handoff" patterns where one agent delegates specialized tasks to another.
 *
 * ## Usage
 *
 * Create a Subagent using one of the factory methods:
 *
 * ```kotlin
 * // From an @Agent annotated class
 * Subagent.ofClass(MyAgent::class.java)
 * Subagent.ofClass<MyAgent>()  // Kotlin reified version
 *
 * // From an agent name (resolved at runtime)
 * Subagent.byName("MyAgent")
 *
 * // From an Agent instance
 * Subagent.ofInstance(resolvedAgent)
 *
 * // From an instance of an @Agent annotated class
 * Subagent.ofAnnotatedInstance(myAgentBean)
 * ```
 *
 * Use with `withTool()` on a PromptRunner:
 *
 * ```kotlin
 * context.ai()
 *     .withTool(Subagent.ofClass(MyAgent::class.java))
 *     .creating(Result::class.java)
 *     .fromPrompt("...")
 * ```
 *
 * For asset tracking, wrap with `AssetAddingTool`:
 *
 * ```kotlin
 * context.ai()
 *     .withTool(assetTracker.addReturnedAssets(Subagent.ofClass(MyAgent::class.java)))
 *     .creating(Result::class.java)
 *     .fromPrompt("...")
 * ```
 *
 * ## Input Type Resolution
 *
 * The input type for the subagent is automatically determined by introspecting
 * the agent's actions. It finds the first non-injected input binding from the
 * agent's first action.
 */
class Subagent private constructor(
    private val agentRef: AgentRef,
    private val textCommunicator: TextCommunicator = PromptedTextCommunicator,
) : Tool {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Sealed interface representing different ways to reference an agent.
     */
    private sealed interface AgentRef {
        val inputClass: Class<*>

        /** Reference to a resolved Agent instance */
        data class FromAgent(val agent: Agent, override val inputClass: Class<*>) : AgentRef

        /** Reference by agent name (resolved at runtime from platform) */
        data class FromName(val name: String, override val inputClass: Class<*>) : AgentRef

        /** Reference to an @Agent annotated class */
        data class FromClass(val clazz: Class<*>, override val inputClass: Class<*>) : AgentRef

        /** Reference to an instance of an @Agent annotated class */
        data class FromAnnotatedInstance(val instance: Any, override val inputClass: Class<*>) : AgentRef
    }

    override val definition: Tool.Definition by lazy {
        createDefinition()
    }

    /**
     * Create the tool definition without requiring an AgentProcess context.
     * This allows the Subagent to be wrapped (e.g., with AssetAddingTool) before being used.
     */
    private fun createDefinition(): Tool.Definition {
        return when (val ref = agentRef) {
            is AgentRef.FromAgent -> {
                object : Tool.Definition {
                    override val name: String = ref.agent.name
                    override val description: String = ref.agent.description
                    override val inputSchema: Tool.InputSchema = TypeBasedInputSchema.of(ref.inputClass)
                }
            }

            is AgentRef.FromClass -> {
                val agenticInfo = AgenticInfo(ref.clazz)
                object : Tool.Definition {
                    override val name: String = agenticInfo.agentName()
                    override val description: String = agenticInfo.agentAnnotation?.description ?: ""
                    override val inputSchema: Tool.InputSchema = TypeBasedInputSchema.of(ref.inputClass)
                }
            }

            is AgentRef.FromAnnotatedInstance -> {
                val agenticInfo = AgenticInfo(ref.instance.javaClass)
                object : Tool.Definition {
                    override val name: String = agenticInfo.agentName()
                    override val description: String = agenticInfo.agentAnnotation?.description ?: ""
                    override val inputSchema: Tool.InputSchema = TypeBasedInputSchema.of(ref.inputClass)
                }
            }

            is AgentRef.FromName -> {
                object : Tool.Definition {
                    override val name: String = ref.name
                    override val description: String = "Delegate to agent '${ref.name}'"
                    override val inputSchema: Tool.InputSchema = TypeBasedInputSchema.of(ref.inputClass)
                }
            }
        }
    }

    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT

    override fun call(input: String): Tool.Result {
        val parentAgentProcess = AgentProcess.get()
            ?: throw IllegalStateException(
                "No parent agent process found. Subagent must be called within an agent context."
            )
        val agentPlatform = parentAgentProcess.processContext.platformServices.agentPlatform
        val agent = resolveAgent(agentPlatform)
        val inputType = agentRef.inputClass

        logger.info("Subagent {} invoked with input: {}", agent.name, input)

        val objectMapper = parentAgentProcess.processContext.platformServices.objectMapper

        val inputObject = try {
            val o = objectMapper.readValue(input, inputType)
            logger.info("Parsed subagent input to {}: {}", inputType.simpleName, o)
            o
        } catch (e: Exception) {
            val errorMessage = "Error parsing subagent input: ${e.message}"
            logger.warn(errorMessage, e)
            return Tool.Result.error(errorMessage, e)
        }

        val processOptions = createProcessOptions(parentAgentProcess)
        val autonomy = parentAgentProcess.processContext.platformServices.autonomy()

        return try {
            val execution = autonomy.runAgent(
                inputObject = inputObject,
                processOptions = processOptions,
                agent = agent,
            )
            logger.info("Subagent {} completed: {}", agent.name, execution)
            // Return result with artifact so AssetAddingTool can track it
            Tool.Result.withArtifact(
                content = textCommunicator.communicateResult(execution),
                artifact = execution.output,
            )
        } catch (pwe: ProcessWaitingException) {
            val response = textCommunicator.communicateAwaitable(agent, pwe)
            logger.info("Subagent {} awaiting: {}", agent.name, response)
            Tool.Result.text(response)
        }
    }

    internal fun resolveAgent(agentPlatform: AgentPlatform): Agent {
        return when (val ref = agentRef) {
            is AgentRef.FromAgent -> ref.agent

            is AgentRef.FromName -> {
                agentPlatform.agents().find { it.name == ref.name }
                    ?: throw IllegalArgumentException(
                        "Subagent '${ref.name}' not found in platform ${agentPlatform.name}. " +
                                "Available agents: ${agentPlatform.agents().map { it.name }}"
                    )
            }

            is AgentRef.FromClass -> {
                val agenticInfo = AgenticInfo(ref.clazz)
                require(agenticInfo.agentic()) {
                    "Class ${ref.clazz.name} must be annotated with @Agent"
                }
                val agentName = agenticInfo.agentName()
                agentPlatform.agents().find { it.name == agentName }
                    ?: throw IllegalArgumentException(
                        "Subagent '$agentName' (from class ${ref.clazz.simpleName}) not found in platform ${agentPlatform.name}. " +
                                "Available agents: ${agentPlatform.agents().map { it.name }}"
                    )
            }

            is AgentRef.FromAnnotatedInstance -> {
                val agenticInfo = AgenticInfo(ref.instance.javaClass)
                require(agenticInfo.agentic()) {
                    "Instance of ${ref.instance.javaClass.name} must be annotated with @Agent"
                }
                val agentName = agenticInfo.agentName()
                agentPlatform.agents().find { it.name == agentName }
                    ?: throw IllegalArgumentException(
                        "Subagent '$agentName' (from instance of ${ref.instance.javaClass.simpleName}) not found in platform ${agentPlatform.name}. " +
                                "Available agents: ${agentPlatform.agents().map { it.name }}"
                    )
            }
        }
    }

    private fun createProcessOptions(parentAgentProcess: AgentProcess): ProcessOptions {
        val blackboard = parentAgentProcess.processContext.blackboard.spawn()
        val parentOutputChannel = parentAgentProcess.processContext.outputChannel
        logger.info(
            "Creating subagent process with spawned blackboard from parent {}",
            parentAgentProcess.id
        )
        return ProcessOptions(
            verbosity = Verbosity(showPrompts = true),
            blackboard = blackboard,
            outputChannel = parentOutputChannel,
        )
    }

    override fun toString(): String = when (val ref = agentRef) {
        is AgentRef.FromAgent -> "Subagent(agent=${ref.agent.name})"
        is AgentRef.FromName -> "Subagent(name=${ref.name})"
        is AgentRef.FromClass -> "Subagent(class=${ref.clazz.simpleName})"
        is AgentRef.FromAnnotatedInstance -> "Subagent(instance=${ref.instance.javaClass.simpleName})"
    }

    /**
     * Builder step that requires specifying the input type.
     * Use [consuming] to complete the Subagent creation.
     */
    sealed class Builder {
        /**
         * Specify the input type that the LLM will provide when invoking this tool.
         * This type will be used to generate the JSON schema for the tool.
         *
         * @param inputClass the input type class
         * @return the configured Subagent tool
         */
        abstract fun consuming(inputClass: Class<*>): Subagent

        /**
         * Specify the input type (Kotlin reified version).
         */
        inline fun <reified I> consuming(): Subagent = consuming(I::class.java)

        /**
         * Specify the input type (KClass version).
         */
        fun consuming(inputClass: KClass<*>): Subagent = consuming(inputClass.java)

        internal class FromClassBuilder(private val agentClass: Class<*>) : Builder() {
            override fun consuming(inputClass: Class<*>): Subagent =
                Subagent(AgentRef.FromClass(agentClass, inputClass))
        }

        internal class FromNameBuilder(private val name: String) : Builder() {
            override fun consuming(inputClass: Class<*>): Subagent =
                Subagent(AgentRef.FromName(name, inputClass))
        }

        internal class FromAgentBuilder(private val agent: Agent) : Builder() {
            override fun consuming(inputClass: Class<*>): Subagent =
                Subagent(AgentRef.FromAgent(agent, inputClass))
        }

        internal class FromAnnotatedInstanceBuilder(private val instance: Any) : Builder() {
            override fun consuming(inputClass: Class<*>): Subagent =
                Subagent(AgentRef.FromAnnotatedInstance(instance, inputClass))
        }
    }

    companion object {

        /**
         * Create a Subagent from an @Agent annotated class.
         * Call [Builder.consuming] to specify the input type.
         *
         * Example:
         * ```
         * Subagent.ofClass(MyAgent.class).consuming(MyInput.class)
         * ```
         *
         * @param agentClass the class annotated with @Agent
         * @return a Builder to specify the input type
         */
        @JvmStatic
        fun ofClass(agentClass: Class<*>): Builder {
            val agenticInfo = AgenticInfo(agentClass)
            require(agenticInfo.agentic()) {
                "Class ${agentClass.name} must be annotated with @Agent"
            }
            return Builder.FromClassBuilder(agentClass)
        }

        /**
         * Create a Subagent from an @Agent annotated class (Kotlin reified version).
         */
        inline fun <reified T> ofClass(): Builder = ofClass(T::class.java)

        /**
         * Create a Subagent from a KClass.
         */
        fun ofClass(agentClass: KClass<*>): Builder = ofClass(agentClass.java)

        /**
         * Create a Subagent by agent name.
         * The agent will be resolved from the platform at runtime.
         * Call [Builder.consuming] to specify the input type.
         *
         * Example:
         * ```
         * Subagent.byName("MyAgent").consuming(MyInput.class)
         * ```
         *
         * @param name the name of the agent to delegate to
         * @return a Builder to specify the input type
         */
        @JvmStatic
        fun byName(name: String): Builder {
            require(name.isNotBlank()) { "Agent name must not be blank" }
            return Builder.FromNameBuilder(name)
        }

        /**
         * Create a Subagent from an already-resolved Agent instance.
         * Call [Builder.consuming] to specify the input type.
         *
         * @param agent the Agent to delegate to
         * @return a Builder to specify the input type
         */
        @JvmStatic
        fun ofInstance(agent: Agent): Builder {
            return Builder.FromAgentBuilder(agent)
        }

        /**
         * Create a Subagent from an instance of an @Agent annotated class.
         * The agent name is extracted from the instance's class metadata.
         * Call [Builder.consuming] to specify the input type.
         *
         * @param annotatedInstance an instance of a class annotated with @Agent
         * @return a Builder to specify the input type
         */
        @JvmStatic
        fun ofAnnotatedInstance(annotatedInstance: Any): Builder {
            val agenticInfo = AgenticInfo(annotatedInstance.javaClass)
            require(agenticInfo.agentic()) {
                "Instance of ${annotatedInstance.javaClass.name} must be annotated with @Agent"
            }
            return Builder.FromAnnotatedInstanceBuilder(annotatedInstance)
        }
    }
}
