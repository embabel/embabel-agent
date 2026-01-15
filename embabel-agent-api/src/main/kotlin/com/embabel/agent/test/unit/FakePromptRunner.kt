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
package com.embabel.agent.test.unit

import com.embabel.agent.api.common.*
import com.embabel.agent.api.common.nested.ObjectCreator
import com.embabel.agent.api.common.nested.TemplateOperations
import com.embabel.agent.api.common.support.DelegatingObjectCreator
import com.embabel.agent.api.common.support.DelegatingTemplateOperations
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.core.support.safelyGetTools
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.MobyNameGenerator
import com.embabel.common.core.types.ZeroToOne
import org.slf4j.LoggerFactory
import java.util.function.Predicate

enum class Method {
    CREATE_OBJECT,
    CREATE_OBJECT_IF_POSSIBLE,
    EVALUATE_CONDITION,
}

data class LlmInvocation(
    val interaction: LlmInteraction,
    val messages: List<Message>,
    val method: Method,
) {
    /**
     * The prompt text (content of all messages concatenated).
     * Convenience property for testing assertions.
     */
    val prompt: String
        get() = messages.joinToString("\n") { it.content }
}

data class FakePromptRunner(
    override val llm: LlmOptions?,
    override val messages: List<Message> = emptyList(),
    override val images: List<AgentImage> = emptyList(),
    override val toolGroups: Set<ToolGroupRequirement>,
    override val toolObjects: List<ToolObject>,
    override val promptContributors: List<PromptContributor>,
    private val contextualPromptContributors: List<ContextualPromptElement>,
    override val generateExamples: Boolean?,
    override val propertyFilter: Predicate<String> = Predicate { true },
    override val validation: Boolean = true,
    private val context: OperationContext,
    private val _llmInvocations: MutableList<LlmInvocation> = mutableListOf(),
    private val responses: MutableList<Any?> = mutableListOf(),
    private val otherTools: List<Tool> = emptyList(),
    /**
     * The interaction ID set via withInteractionId() or withId().
     * Can be inspected in tests to verify the correct ID was set.
     */
    val interactionId: InteractionId? = null,
) : PromptRunner {

    private val logger = LoggerFactory.getLogger(FakePromptRunner::class.java)

    init {
        logger.info("Fake prompt runner created: ${hashCode()}")
    }

    /**
     * Internal adapter that implements PromptExecutionDelegate for use with delegating implementations.
     */
    private inner class DelegateAdapter : PromptExecutionDelegate {
        override val templateRenderer: com.embabel.common.textio.template.TemplateRenderer
            get() = context.agentPlatform().platformServices.templateRenderer

        override val objectMapper: com.fasterxml.jackson.databind.ObjectMapper
            get() = context.agentPlatform().platformServices.objectMapper

        override val llm: LlmOptions?
            get() = this@FakePromptRunner.llm

        override val messages: List<Message>
            get() = this@FakePromptRunner.messages

        override val images: List<AgentImage>
            get() = this@FakePromptRunner.images

        override val toolGroups: Set<ToolGroupRequirement>
            get() = this@FakePromptRunner.toolGroups

        override val toolObjects: List<ToolObject>
            get() = this@FakePromptRunner.toolObjects

        override val promptContributors: List<PromptContributor>
            get() = this@FakePromptRunner.promptContributors

        override val generateExamples: Boolean?
            get() = this@FakePromptRunner.generateExamples

        override val propertyFilter: Predicate<String>
            get() = this@FakePromptRunner.propertyFilter

        override val validation: Boolean
            get() = this@FakePromptRunner.validation

        override fun withInteractionId(interactionId: InteractionId): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(interactionId = interactionId).DelegateAdapter()
        }

        override fun withLlm(llm: LlmOptions): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(llm = llm).DelegateAdapter()
        }

        override fun withMessages(messages: List<Message>): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(messages = this@FakePromptRunner.messages + messages).DelegateAdapter()
        }

        override fun withImages(images: List<AgentImage>): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(images = this@FakePromptRunner.images + images).DelegateAdapter()
        }

        override fun withToolGroup(toolGroup: ToolGroupRequirement): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(toolGroups = this@FakePromptRunner.toolGroups + toolGroup)
                .DelegateAdapter()
        }

        override fun withToolGroup(toolGroup: ToolGroup): PromptExecutionDelegate {
            TODO("Not yet implemented")
        }

        override fun withToolObject(toolObject: ToolObject): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(toolObjects = this@FakePromptRunner.toolObjects + toolObject)
                .DelegateAdapter()
        }

        override fun withTool(tool: Tool): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(otherTools = this@FakePromptRunner.otherTools + tool).DelegateAdapter()
        }

        override fun withHandoffs(vararg outputTypes: Class<*>): PromptExecutionDelegate {
            TODO("Implement handoff support")
        }

        override fun withSubagents(vararg subagents: Subagent): PromptExecutionDelegate {
            TODO("Implement subagent handoff support")
        }

        override fun withPromptContributors(promptContributors: List<PromptContributor>): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(
                promptContributors = this@FakePromptRunner.promptContributors + promptContributors
            ).DelegateAdapter()
        }

        override fun withContextualPromptContributors(
            contextualPromptContributors: List<ContextualPromptElement>,
        ): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(
                contextualPromptContributors = this@FakePromptRunner.contextualPromptContributors + contextualPromptContributors
            ).DelegateAdapter()
        }

        override fun withGenerateExamples(generateExamples: Boolean): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(generateExamples = generateExamples).DelegateAdapter()
        }

        override fun withPropertyFilter(filter: Predicate<String>): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(propertyFilter = this@FakePromptRunner.propertyFilter.and(filter))
                .DelegateAdapter()
        }

        override fun withValidation(validation: Boolean): PromptExecutionDelegate {
            return this@FakePromptRunner.copy(validation = validation).DelegateAdapter()
        }

        override fun <T> createObject(messages: List<Message>, outputClass: Class<T>): T {
            return this@FakePromptRunner.createObject(messages, outputClass)
        }

        override fun <T> createObjectIfPossible(messages: List<Message>, outputClass: Class<T>): T? {
            return this@FakePromptRunner.createObjectIfPossible(messages, outputClass)
        }

        override fun respond(messages: List<Message>): com.embabel.chat.AssistantMessage {
            TODO("Not yet implemented in FakePromptRunner")
        }

        override fun evaluateCondition(condition: String, context: String, confidenceThreshold: ZeroToOne): Boolean {
            return this@FakePromptRunner.evaluateCondition(condition, context, confidenceThreshold)
        }
    }

    override fun withInteractionId(interactionId: InteractionId): PromptRunner =
        copy(interactionId = interactionId)


    override fun withMessages(messages: List<Message>): PromptRunner =
        copy(messages = this.messages + messages)

    override fun withImages(images: List<AgentImage>): PromptRunner =
        copy(images = this.images + images)

    /**
     * Add a response to the list of expected responses.
     * This is used to simulate responses from the LLM.
     */
    fun expectResponse(response: Any?) {
        responses.add(response)
        logger.info(
            "Expected response added: ${response?.javaClass?.name ?: "null"}"
        )
    }

    private fun <T> getResponse(outputClass: Class<T>): T? {
        if (responses.size < llmInvocations.size) {
            throw IllegalStateException(
                """
                    Expected ${llmInvocations.size} responses, but got ${responses.size}.
                    Make sure to call expectResponse() for each LLM invocation.
                    """.trimIndent()
            )
        }
        val maybeT = responses[llmInvocations.size - 1]
        if (maybeT == null) {
            return null
        }
        if (!outputClass.isInstance(maybeT)) {
            throw IllegalStateException(
                "Expected response of type ${outputClass.name}, but got ${maybeT.javaClass.name}."
            )
        }
        return maybeT as T
    }

    /**
     * The LLM calls that were made
     */
    val llmInvocations: List<LlmInvocation>
        get() = _llmInvocations

    override fun <T> createObject(
        prompt: String,
        outputClass: Class<T>,
    ): T {
        _llmInvocations += LlmInvocation(
            interaction = createLlmInteraction(),
            messages = listOf(UserMessage(prompt)),
            method = Method.CREATE_OBJECT,
        )
        return getResponse(outputClass)!!
    }

    override fun <T> createObjectIfPossible(
        messages: List<Message>,
        outputClass: Class<T>,
    ): T? {
        _llmInvocations += LlmInvocation(
            interaction = createLlmInteraction(),
            messages = messages,
            method = Method.CREATE_OBJECT_IF_POSSIBLE,
        )
        return getResponse(outputClass)
    }

    override fun <T> createObject(
        messages: List<Message>,
        outputClass: Class<T>,
    ): T {
        return createObject(prompt = messages.joinToString(), outputClass = outputClass)
    }

    override fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne,
    ): Boolean {
        _llmInvocations += LlmInvocation(
            interaction = createLlmInteraction(),
            messages = listOf(UserMessage(condition)),
            method = Method.EVALUATE_CONDITION,
        )
        return true
    }

    override fun withLlm(llm: LlmOptions): PromptRunner =
        copy(llm = llm)

    override fun withToolGroup(toolGroup: ToolGroupRequirement): PromptRunner =
        copy(toolGroups = this.toolGroups + toolGroup)

    override fun withToolObject(toolObject: ToolObject): PromptRunner =
        copy(toolObjects = this.toolObjects + toolObject)

    override fun withPromptContributors(promptContributors: List<PromptContributor>): PromptRunner =
        copy(promptContributors = this.promptContributors + promptContributors)

    override fun withContextualPromptContributors(
        contextualPromptContributors: List<ContextualPromptElement>,
    ): PromptRunner =
        copy(contextualPromptContributors = this.contextualPromptContributors + contextualPromptContributors)

    override fun withGenerateExamples(generateExamples: Boolean): PromptRunner =
        copy(generateExamples = generateExamples)

    @Deprecated("Use creating().withPropertyFilter() instead")
    override fun withPropertyFilter(filter: Predicate<String>): PromptRunner =
        copy(propertyFilter = this.propertyFilter.and(filter))

    @Deprecated("Use creating().withValidation() instead")
    override fun withValidation(validation: Boolean): PromptRunner =
        copy(validation = validation)


    private fun createLlmInteraction() =
        LlmInteraction(
            llm = llm ?: LlmOptions(),
            toolGroups = this.toolGroups + toolGroups,
            tools = safelyGetTools(toolObjects) + otherTools,
            promptContributors = promptContributors + contextualPromptContributors.map {
                it.toPromptContributor(
                    context
                )
            },
            id = interactionId ?: InteractionId(MobyNameGenerator.generateName()),
            generateExamples = generateExamples,
        )

    override fun withTemplate(templateName: String): TemplateOperations {
        return DelegatingTemplateOperations(
            delegate = DelegateAdapter(),
            templateName = templateName,
        )
    }

    override fun withHandoffs(vararg outputTypes: Class<*>): PromptRunner {
        TODO("Implement handoff support")
    }

    override fun withSubagents(vararg subagents: Subagent): PromptRunner {
        TODO("Implement subagent handoff support")
    }

    override fun withToolGroup(toolGroup: ToolGroup): PromptRunner {
        TODO("Not yet implemented")
    }

    override fun withTool(tool: Tool): PromptRunner =
        copy(otherTools = this.otherTools + tool)

    override fun <T> creating(outputClass: Class<T>): ObjectCreator<T> {
        return DelegatingObjectCreator(
            delegate = DelegateAdapter(),
            outputClass = outputClass,
        )
    }
}
