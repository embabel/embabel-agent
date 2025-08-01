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
package com.embabel.agent.testing.unit

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.core.support.safelyGetToolCallbacks
import com.embabel.agent.prompt.element.ContextualPromptElement
import com.embabel.agent.spi.InteractionId
import com.embabel.agent.spi.LlmInteraction
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.MobyNameGenerator
import com.embabel.common.core.types.ZeroToOne
import org.slf4j.LoggerFactory

enum class Method {
    CREATE_OBJECT,
    CREATE_OBJECT_IF_POSSIBLE,
    EVALUATE_CONDITION,
}

data class LlmInvocation(
    val interaction: LlmInteraction,
    val prompt: String,
    val method: Method,
)

data class FakePromptRunner(
    override val llm: LlmOptions?,
    override val toolGroups: Set<ToolGroupRequirement>,
    override val toolObjects: List<ToolObject>,
    override val promptContributors: List<PromptContributor>,
    private val contextualPromptContributors: List<ContextualPromptElement>,
    override val generateExamples: Boolean?,
    private val context: OperationContext,
    private val _llmInvocations: MutableList<LlmInvocation> = mutableListOf(),
    private val responses: MutableList<Any?> = mutableListOf(),
) : PromptRunner {

    private val logger = LoggerFactory.getLogger(FakePromptRunner::class.java)

    init {
        logger.info("Fake prompt runner created: ${hashCode()}")
    }

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
                "Expected response of type ${outputClass.name}, but got ${maybeT?.javaClass?.name ?: "null"}."
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
            prompt = prompt,
            method = Method.CREATE_OBJECT,
        )
        return getResponse(outputClass)!!
    }

    override fun <T> createObjectIfPossible(
        prompt: String,
        outputClass: Class<T>,
    ): T? {
        _llmInvocations += LlmInvocation(
            interaction = createLlmInteraction(),
            prompt = prompt,
            method = Method.CREATE_OBJECT_IF_POSSIBLE,
        )
        return getResponse(outputClass)
    }

    override fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne,
    ): Boolean {
        _llmInvocations += LlmInvocation(
            interaction = createLlmInteraction(),
            prompt = condition,
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

    private fun createLlmInteraction() =
        LlmInteraction(
            llm = llm ?: LlmOptions(),
            toolGroups = this.toolGroups + toolGroups,
            toolCallbacks = safelyGetToolCallbacks(toolObjects),
            promptContributors = promptContributors + contextualPromptContributors.map {
                it.toPromptContributor(
                    context
                )
            },
            id = InteractionId(
                MobyNameGenerator.generateName(
                )
            ),
            generateExamples = generateExamples,
        )

    override fun withHandoffs(vararg inputTypes: Class<*>): PromptRunner {
        TODO("Implement handoff support")
    }
}
