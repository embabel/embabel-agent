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
package com.embabel.agent.api.common

import com.embabel.agent.api.annotation.support.AgenticInfo
import com.embabel.agent.api.common.PromptRunner.Creating
import com.embabel.agent.api.common.nested.TemplateOperations
import com.embabel.agent.api.common.thinking.ThinkingPromptRunnerOperations
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.validation.guardrails.GuardRail
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.core.support.LlmUse
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.ai.prompt.PromptElement
import com.embabel.common.core.streaming.StreamingCapability
import com.embabel.common.util.loggerFor
import org.jetbrains.annotations.ApiStatus
import java.util.function.Predicate
import kotlin.reflect.KProperty1

/**
 * Define a handoff to a subagent.

 */
class Subagent private constructor(
    private val agentRef: Any,
    val inputClass: Class<*>,
) {

    /**
     * Subagent that is an agent
     * @param agent the subagent to hand off to
     * @param inputClass the class of the input that the subagent expects
     */
    constructor(
        agent: Agent,
        inputClass: Class<*>,
    ) : this(
        agentRef = agent,
        inputClass = inputClass,
    )

    constructor(
        agentName: String,
        inputClass: Class<*>,
    ) : this(
        agentRef = agentName,
        inputClass = inputClass,
    )

    /**
     * Reference to an annotated agent class.
     */
    constructor(
        agentType: Class<*>,
        inputClass: Class<*>,
    ) : this(
        agentRef = agentType,
        inputClass = inputClass,
    )

    fun resolve(agentPlatform: AgentPlatform): Agent {
        return when (agentRef) {
            is Agent -> agentRef
            is String -> agentPlatform.agents().find { it.name == agentRef }
                ?: throw IllegalArgumentException(
                    "Subagent with name '$agentRef' not found in platform ${agentPlatform.name}. " +
                            "Available agents: ${agentPlatform.agents().map { it.name }}"
                )

            is Class<*> -> {
                val agenticInfo = AgenticInfo(agentRef)
                if (!agenticInfo.agentic()) {
                    throw IllegalArgumentException(
                        "Subagent must be an Agent or a String representing the agent name, but was: $agentRef"
                    )
                }
                agentPlatform.agents().find { it.name == agenticInfo.agentName() }
                    ?: throw IllegalArgumentException(
                        "Subagent of type $agentRef with name '$agentRef' not found in platform ${agentPlatform.name}. " +
                                "Available agents: ${agentPlatform.agents().map { it.name }}"
                    )
            }

            else -> throw IllegalArgumentException(
                "Subagent must be an Agent or a String representing the agent name, but was: $agentRef"
            )
        }
    }
}


/**
 * User code should always use this interface to execute prompts.
 * Typically obtained from an [OperationContext] or [ActionContext] parameter,
 * via [OperationContext.ai]
 * A PromptRunner is immutable once constructed, and has determined
 * LLM and hyperparameters. Use the "with" methods to evolve
 * the state to your desired configuration before executing createObject,
 * generateText or other LLM invocation methods.
 * Thus, a PromptRunner can be reused within an action implementation.
 * A contextual facade to LlmOperations.
 * @see com.embabel.agent.spi.LlmOperations
 */
interface PromptRunner : LlmUse, PromptRunnerOperations {

    /**
     * Additional objects with @Tool annotation for use in this PromptRunner
     */
    val toolObjects: List<ToolObject>

    /**
     * Messages added to this PromptRunner
     */
    val messages: List<Message>

    /**
     * Images added to this PromptRunner
     */
    val images: List<AgentImage>

    /**
     * Set an interaction id for this prompt runner.
     */
    fun withInteractionId(interactionId: InteractionId): PromptRunner

    /**
     * Set the interaction id for this prompt runner.
     */
    fun withId(id: String) = withInteractionId(InteractionId(id))

    /**
     * Specify an LLM for the PromptRunner
     */
    fun withLlm(llm: LlmOptions): PromptRunner

    /**
     * Add a message that will be included in the final prompt.
     */
    fun withMessage(message: Message): PromptRunner =
        withMessages(listOf(message))

    fun withMessages(messages: List<Message>): PromptRunner

    fun withMessages(vararg message: Message): PromptRunner =
        withMessages(message.toList())

    /**
     * Add an image that will be included in the final prompt.
     * Images will be combined with the prompt text when operations are executed.
     */
    fun withImage(image: AgentImage): PromptRunner =
        withImages(listOf(image))

    fun withImages(images: List<AgentImage>): PromptRunner

    fun withImages(vararg images: AgentImage): PromptRunner =
        withImages(images.toList())

    /**
     * Add a tool group to the PromptRunner
     * @param toolGroup name of the toolGroup we're requesting
     * @return PromptRunner instance with the added tool group
     */
    fun withToolGroup(toolGroup: String): PromptRunner =
        withToolGroup(ToolGroupRequirement(toolGroup))

    /**
     * Allows for dynamic tool groups to be added to the PromptRunner.
     */
    fun withToolGroup(toolGroup: ToolGroup): PromptRunner

    fun withToolGroups(toolGroups: Set<String>): PromptRunner =
        toolGroups.fold(this) { acc, toolGroup -> acc.withToolGroup(toolGroup) }

    /**
     * Add a set of tool groups to the PromptRunner
     * @param toolGroups the set of named tool groups to add
     */
    fun withTools(vararg toolGroups: String): PromptRunner =
        withToolGroups(toolGroups.toSet())

    fun withToolGroup(toolGroup: ToolGroupRequirement): PromptRunner

    /**
     * Add a tool object to the prompt runner.
     * The tool object should have @Tool annotations.
     * @param toolObject the object to add. If it is null or has no Tool annotations, nothing is done.
     * This is not an error
     * @return PromptRunner instance with the added tool object
     */
    fun withToolObject(toolObject: Any?): PromptRunner =
        if (toolObject == null) {
            this
        } else {
            withToolObject(ToolObject.from(toolObject))
        }

    /**
     * Add a tool object
     * @param toolObject the object to add.
     */
    fun withToolObject(
        toolObject: ToolObject,
    ): PromptRunner

    fun withToolObjectInstances(vararg toolObjects: Any?): PromptRunner =
        toolObjects.fold(this) { acc, toolObject -> acc.withToolObject(toolObject) }

    fun withToolObjects(toolObjects: List<Any>): PromptRunner =
        toolObjects.fold(this) { acc, toolObject -> acc.withToolObject(toolObject) }

    /**
     * Add a framework-agnostic [Tool] to the prompt runner.
     *
     * @param tool the tool to add
     * @return PromptRunner instance with the added tool
     */
    fun withTool(tool: Tool): PromptRunner

    /**
     * Add multiple framework-agnostic [Tool]s to the prompt runner.
     *
     * @param tools the tools to add
     * @return PromptRunner instance with the added tools
     */
    fun withTools(tools: List<Tool>): PromptRunner =
        tools.fold(this) { acc, tool -> acc.withTool(tool) }

    /**
     * Add multiple framework-agnostic [Tool]s to the prompt runner (varargs version).
     *
     * @param tools the tools to add
     * @return PromptRunner instance with the added tools
     */
    fun withFunctionTools(vararg tools: Tool): PromptRunner =
        withTools(tools.toList())

    /**
     * Add a reference which provides tools and prompt contribution.
     */
    fun withReference(reference: LlmReference): PromptRunner {
        return withToolObject(reference.toolObject())
            .withTools(reference.tools())
            .withPromptContributor(reference)
    }

    /**
     * Add a list of references which provide tools and prompt contributions.
     */
    fun withReferences(references: List<LlmReference>): PromptRunner {
        return references.fold(this) { acc, reference -> acc.withReference(reference) }
    }

    /**
     * Add varargs of references which provide tools and prompt contributions.
     */
    fun withReferences(vararg references: LlmReference): PromptRunner =
        withReferences(references.toList())

    /**
     * Add a list of handoffs to agents on this platform
     * @param outputTypes the types of objects that can result from output flow
     */
    @ApiStatus.Experimental
    fun withHandoffs(
        vararg outputTypes: Class<*>,
    ): PromptRunner

    /**
     * Add a list of subagents to hand off to.
     */
    @ApiStatus.Experimental
    fun withSubagents(
        vararg subagents: Subagent,
    ): PromptRunner

    /**
     * Add a literal system prompt
     */
    fun withSystemPrompt(systemPrompt: String): PromptRunner =
        withPromptContributor(
            PromptContributor.fixed(systemPrompt)
        )

    /**
     * Add a prompt contributor that can add to the prompt.
     * Facilitates reuse of prompt elements.
     * @param promptContributor
     * @return PromptRunner instance with the added PromptContributor
     */
    fun withPromptContributor(promptContributor: PromptContributor): PromptRunner =
        withPromptContributors(listOf(promptContributor))

    fun withPromptContributors(promptContributors: List<PromptContributor>): PromptRunner

    /**
     * Add varargs of prompt contributors and contextual prompt elements.
     */
    fun withPromptElements(vararg elements: PromptElement): PromptRunner {
        val promptContributors = elements.filterIsInstance<PromptContributor>()
        val contextualPromptElements = elements.filterIsInstance<ContextualPromptElement>()
        val oddOnesOut = elements.filterNot { it is PromptContributor || it is ContextualPromptElement }
        if (oddOnesOut.isNotEmpty()) {
            loggerFor<PromptRunner>().warn(
                "{} arguments to withPromptElements were not prompt contributors or contextual prompt elements and will be ignored: {}",
                oddOnesOut.size,
                oddOnesOut.joinToString(
                    ", ", prefix = "[", postfix = "]"
                )
            )
        }
        return withPromptContributors(promptContributors)
            .withContextualPromptContributors(contextualPromptElements)
    }

    /**
     * Add a prompt contributor that can see context
     */
    fun withContextualPromptContributors(
        contextualPromptContributors: List<ContextualPromptElement>,
    ): PromptRunner

    fun withContextualPromptContributor(
        contextualPromptContributor: ContextualPromptElement,
    ): PromptRunner =
        withContextualPromptContributors(listOf(contextualPromptContributor))

    /**
     * Set whether to generate examples of the output in the prompt
     * on a per-PromptRunner basis. This overrides platform defaults.
     * Note that adding individual examples with [Creating.withExample]
     * will always override this.
     */
    fun withGenerateExamples(generateExamples: Boolean): PromptRunner

    /**
     * Adds a filter that determines which properties are to be included when creating an object.
     *
     * Note that each predicate is applied *in addition to* previously registered predicates.
     * @param filter the property predicate to be added
     * @deprecated Use creating().withPropertyFilter() instead. Will be removed when old ObjectCreator implementation is replaced.
     */
    @Deprecated(
        "Use creating().withPropertyFilter() instead",
        ReplaceWith("creating(outputClass).withPropertyFilter(filter)")
    )
    fun withPropertyFilter(filter: Predicate<String>): PromptRunner

    /**
     * Set whether to validate created objects.
     * @param validation `true` to validate created objects; `false` otherwise. Defaults to `true`.
     * @deprecated Use creating().withValidation() instead. Will be removed when old ObjectCreator implementation is replaced.
     */
    @Deprecated(
        "Use creating().withValidation() instead",
        ReplaceWith("creating(outputClass).withValidation(validation)")
    )
    fun withValidation(validation: Boolean = true): PromptRunner

    /**
     * Add guardrail instances to this PromptRunner (additive).
     *
     * @param guards the guardrail instances to add
     * @return PromptRunner instance with additional guardrails configured
     */
    fun withGuardRails(vararg guards: GuardRail): PromptRunner

    /**
     * Returns a mode for creating strongly-typed objects.
     *
     * @param T the type of object to create
     * @param outputClass the class of objects to create
     * @return creating mode supporting examples, property filtering, and validation
     */
    fun <T> creating(outputClass: Class<T>): Creating<T>

    /**
     * Use operations from a given template
     */
    @Deprecated(
        "Use rendering(templateName) instead",
        ReplaceWith("rendering(templateName)")
    )
    fun withTemplate(templateName: String): TemplateOperations = rendering(templateName)

    /**
     * Return a [TemplateOperations] for rendering the specified template.
     *
     * @param templateName the name of the template to render
     * @return template operations for creating objects and generating text
     */
    fun rendering(templateName: String): TemplateOperations

    /**
     * Check if true reactive streaming is supported by the underlying LLM model.
     * Always check this before calling stream() to avoid exceptions.
     *
     * @return true if real-time streaming is supported, false if streaming is not available
     */
    fun supportsStreaming(): Boolean = false

    /**
     * Create streaming operations for this prompt runner configuration.
     *
     * This follows an explicit failure policy - if streaming is not supported by the
     * underlying LLM implementation, this method will throw an exception rather than
     * providing fallback behavior. Always check supportsStreaming() first for safe usage.
     *
     * @return StreamingCapability instance providing access to streaming operations
     * @throws UnsupportedOperationException if streaming is not supported by this implementation
     */
    @Deprecated(
        "Use streaming() instead",
        ReplaceWith("streaming()")
    )
    fun stream(): StreamingCapability = streaming()

    /**
     * Return a [StreamingCapability] for reactive streaming operations.
     * Throws an exception if the underlying LLM does not support streaming.
     * Use [supportsStreaming] to check availability before calling.
     *
     * @return streaming capability for reactive object and text generation
     * @throws UnsupportedOperationException if streaming is not supported
     */
    fun streaming(): StreamingCapability {
        throw UnsupportedOperationException(
            "Streaming not supported by this PromptRunner implementation. " +
                    "Check supportsStreaming() before calling stream()."
        )
    }

    /**
     * Check if thinking extraction capabilities are supported by the underlying implementation.
     *
     * Thinking capabilities allow extraction of thinking blocks (like `<think>...</think>`)
     * from LLM responses and provide access to both the result and the extracted thinking content.
     * Always check this before calling thinking() to avoid exceptions.
     *
     * Note: Thinking and streaming capabilities are mutually exclusive.
     *
     * @return true if thinking extraction is supported, false if thinking is not available
     */
    fun supportsThinking(): Boolean = false

    /**
     * Create a thinking-enhanced version of this prompt runner.
     *
     * Returns a PromptRunner where all operations (createObject, generateText, etc.)
     * return ThinkingResponse<T> wrappers that include both results and extracted
     * thinking blocks from the LLM response.
     *
     * Always check supportsThinking() first and ensure LlmOptions includes thinking configuration
     * via withLlm(LlmOptions.withThinking(Thinking.withExtraction())).
     *
     * Note: Thinking and streaming capabilities are mutually exclusive.
     *
     * @return ThinkingCapability instance providing access to thinking-aware operations
     * @throws UnsupportedOperationException if thinking is not supported by this implementation
     * @throws IllegalArgumentException if thinking is not enabled in LlmOptions configuration
     */
    @Deprecated(
        message = "Use thinking() instead",
        replaceWith = ReplaceWith("thinking()")
    )
    fun withThinking(): ThinkingPromptRunnerOperations = thinking()

    /**
     * Return a [ThinkingPromptRunnerOperations] for extracting thinking blocks.
     * Throws an exception if the underlying LLM does not support thinking extraction.
     * Use [supportsThinking] to check availability before calling.
     *
     * @return thinking operations returning results with extracted reasoning
     * @throws UnsupportedOperationException if thinking is not supported
     */
    fun thinking(): ThinkingPromptRunnerOperations {
        if (!supportsThinking()) {
            throw UnsupportedOperationException(
                """
                Thinking not supported by this PromptRunner implementation.
                Check supportsThinking() before calling withThinking().
                """.trimIndent()
            )
        }

        val thinking = llm?.thinking
        require(thinking != null && thinking != Thinking.NONE) {
            """
            Thinking capability requires thinking to be enabled in LlmOptions.
            Use withLlm(LlmOptions.withThinking(Thinking.withExtraction()))
            """.trimIndent()
        }

        // For implementations that support thinking but haven't overridden withThinking(),
        // they should provide their own implementation
        error("Implementation error: supportsThinking() returned true but withThinking() not overridden")
    }

    override fun respond(
        messages: List<Message>,
    ): AssistantMessage =
        AssistantMessage(
            createObject(
                messages = this.messages + messages,
                outputClass = String::class.java,
            )
        )


    /**
     * Fluent interface for creating strongly-typed objects from LLM responses.
     * Provides configuration options for:
     *
     * - Adding examples
     * - Filtering properties
     * - Enabling/disabling validation
     *
     * Instances are obtained via [PromptRunner.creating].
     *
     * @param T the type of object to create
     */
    interface Creating<T> {

        /**
         * Add an example of the desired output to the prompt.
         * This will be included in JSON.
         * It is possible to call this method multiple times.
         * This will override PromptRunner.withGenerateExamples
         * @param description description of the example
         * @param value the example object
         * @return this instance for method chaining
         */
        fun withExample(
            description: String,
            value: T,
        ): Creating<T> = withExample(
            CreationExample(
                description = description,
                value = value,
            ),
        )

        /**
         * Add multiple examples from a list or other iterable.
         * Each example will be added as a prompt contributor to improve LLM output quality.
         *
         * @param examples the examples to add
         * @return this instance for method chaining
         */
        fun withExamples(examples: Iterable<CreationExample<T>>): Creating<T> {
            var result: Creating<T> = this
            examples.forEach {
                result = result.withExample(it)
            }
            return result
        }

        /**
         * Add multiple examples using vararg syntax.
         * Each example will be added as a prompt contributor to improve LLM output quality.
         *
         * @param examples the examples to add
         * @return this instance for method chaining
         */
        fun withExamples(vararg examples: CreationExample<T>): Creating<T> =
            withExamples(examples.asIterable())

        /**
         * Add an example of the desired output to the prompt.
         * This will be included in JSON.
         * It is possible to call this method multiple times.
         * This will override PromptRunner.withGenerateExamples
         *
         * @param example the example to add
         * @return this instance for method chaining
         */
        fun withExample(
            example: CreationExample<T>,
        ): Creating<T>

        /**
         * Add a filter that determines which properties are to be included when creating an object.
         *
         * Note that each predicate is applied *in addition to* previously registered predicates, including
         * [withProperties] and [withoutProperties].
         *
         * @param filter the property predicate to be added
         * @return this instance for method chaining
         */
        fun withPropertyFilter(filter: Predicate<String>): Creating<T>

        /**
         * Include the given properties when creating an object.
         *
         * Note that each predicate is applied *in addition to* previously registered predicates, including
         * [withPropertyFilter] and [withoutProperties].
         *
         * @param properties the properties that are to be included
         * @return this instance for method chaining
         */
        fun withProperties(vararg properties: String): Creating<T> = withPropertyFilter { properties.contains(it) }

        /**
         * Exclude the given properties when creating an object.
         *
         * Note that each predicate is applied *in addition to* previously registered predicates, including
         * [withPropertyFilter] and [withProperties].
         *
         * @param properties the properties to be excluded
         * @return this instance for method chaining
         */
        fun withoutProperties(vararg properties: String): Creating<T> =
            withPropertyFilter { !properties.contains(it) }

        /**
         * Set whether to validate created objects.
         *
         * @param validation `true` to validate created objects; `false` otherwise. Defaults to `true`.
         * @return this instance for method chaining
         */
        fun withValidation(validation: Boolean = true): Creating<T>

        /**
         * Disables validation of created objects.
         *
         * @return this instance for method chaining
         */
        fun withoutValidation(): Creating<T> = withValidation(false)

        /**
         * Create an object of the desired type using the given prompt and LLM options from context
         * (process context or implementing class).
         * Prompts are typically created within the scope of an @Action method that provides access to
         * domain object instances, offering type safety.
         *
         * @param prompt the prompt text to send to the LLM
         * @return the created object of type T
         */
        fun fromPrompt(
            prompt: String,
        ): T = fromMessages(
            messages = listOf(UserMessage(prompt)),
        )

        /**
         * Create an object of this type from the given template.
         *
         * @param templateName the name of the template to render
         * @param model the model data to use for template rendering
         * @return the created object of type T
         */
        fun fromTemplate(
            templateName: String,
            model: Map<String, Any>,
        ): T

        /**
         * Create an object of the desired type from messages.
         *
         * @param messages the conversation messages to send to the LLM
         * @return the created object of type T
         */
        fun fromMessages(
            messages: List<Message>,
        ): T
    }

}

/**
 * An example of creating an object of the given type.
 * Used to provide strongly typed examples to the ObjectCreator.
 * @param T the type of object to create
 * @param description description of the example--e.g. "good example, correct amount of detail"
 * @param value the example object
 */
// TODO: open class because of extension by ObjectCreationExample, replace with data class once ObjectCreationExample has been removed
open class CreationExample<T>(
    val description: String,
    val value: T,
) {
    open fun copy(
        description: String = this.description,
        value: T = this.value,
    ): CreationExample<T> = CreationExample(description, value)

    override fun toString(): String =
        "CreationExample(description='$description', value=$value)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return if (other is CreationExample<*>) {
            description == other.description && value == other.value
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        var result = description.hashCode()
        result = 31 * result + value.hashCode()
        return result
    }
}



/**
 * Create an object of the given type
 */
inline infix fun <reified T> PromptRunner.createObject(prompt: String): T =
    createObject(prompt, T::class.java)

/**
 * Create an object of the given type.
 * Method overloading is evil
 **/
inline infix fun <reified T> PromptRunner.create(prompt: String): T =
    createObject(prompt, T::class.java)

inline fun <reified T> PromptRunner.createObjectIfPossible(prompt: String): T? =
    createObjectIfPossible(prompt, T::class.java)

inline fun <reified T> TemplateOperations.createObject(
    model: Map<String, Any>,
): T =
    createObject(outputClass = T::class.java, model = model)


/**
 * Includes the given properties when creating an object.
 *
 * Note that each predicate is applied *in addition to* previously registered predicates, including
 * [ObjectCreator::withPropertyFilter], [ObjectCreator::withProperties], [ObjectCreator::withoutProperties],
 * and [withoutProperties].
 * @param properties the properties that are to be included
 */
fun <T, Any> Creating<T>.withProperties(
    vararg properties: KProperty1<T, Any>,
): Creating<T> =
    withProperties(*properties.map { it.name }.toTypedArray())

/**
 * Excludes the given properties when creating an object.
 *
 * Note that each predicate is applied *in addition to* previously registered predicates, including
 * [ObjectCreator::withPropertyFilter], [ObjectCreator::withProperties], [ObjectCreator::withoutProperties],
 * and [withProperties].
 * @param properties the properties that are to be included
 */
fun <T, Any> Creating<T>.withoutProperties(
    vararg properties: KProperty1<T, Any>,
): Creating<T> =
    withoutProperties(*properties.map { it.name }.toTypedArray())
