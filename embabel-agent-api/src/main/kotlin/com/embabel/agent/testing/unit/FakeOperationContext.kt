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
import com.embabel.agent.common.Constants.EMBABEL_PROVIDER
import com.embabel.agent.core.*
import com.embabel.agent.prompt.element.ContextualPromptElement
import com.embabel.agent.testing.integration.IntegrationTestUtils.dummyProcessContext
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import org.slf4j.LoggerFactory

val DummyAgent = Agent(
    name = "Dummy Agent",
    provider = EMBABEL_PROVIDER,
    description = "A dummy agent for testing purposes",
    actions = emptyList(),
    goals = emptySet(),
)

/**
 * Pass into unit tests.
 * Principally used to obtain a [PromptRunner] for testing purposes.
 */
class FakeOperationContext(
    val agent: Agent = DummyAgent,
    override val processContext: ProcessContext = dummyProcessContext(agent = agent),
    override val operation: Operation = FakeAction(name = "test"),
    override val toolGroups: Set<ToolGroupRequirement> = emptySet(),
) : OperationContext, Blackboard by processContext.agentProcess {

    val promptRunner: FakePromptRunner = FakePromptRunner(
        llm = null,
        toolGroups = toolGroups,
        toolObjects = emptyList(),
        promptContributors = emptyList(),
        contextualPromptContributors = emptyList(),
        generateExamples = null,
        context = this,
    )

    private val logger = LoggerFactory.getLogger(FakeOperationContext::class.java)

    init {
        logger.info("FakeOperationContext created: ${hashCode()}: PromptRunner: ${promptRunner.hashCode()}")
    }

    val llmInvocations get() = promptRunner.llmInvocations

    /**
     * Add a response to the list of expected responses.
     * This is used to simulate responses from the LLM.
     */
    fun expectResponse(response: Any?) {
        promptRunner.expectResponse(response)
    }

    override fun promptRunner(
        llm: LlmOptions,
        toolGroups: Set<ToolGroupRequirement>,
        toolObjects: List<ToolObject>,
        promptContributors: List<PromptContributor>,
        contextualPromptContributors: List<ContextualPromptElement>,
        generateExamples: Boolean,
    ): PromptRunner {
        return promptRunner
            .withLlm(llm)
            .let { runner -> toolGroups.fold(runner) { acc, tg -> acc.withToolGroup(tg) } }
            .let { runner -> toolObjects.fold(runner) { acc, to -> acc.withToolObject(to) } }
            .let { runner -> promptContributors.fold(runner) { acc, pc -> acc.withPromptContributor(pc) } }
            .withGenerateExamples(generateExamples)
    }

    companion object {

        @JvmOverloads
        @JvmStatic
        fun create(
            agent: Agent = DummyAgent,
            processContext: ProcessContext = dummyProcessContext(agent = agent),
            operation: Operation = FakeAction(name = "test"),
            toolGroups: Set<ToolGroupRequirement> = emptySet(),
        ) = FakeOperationContext(
            processContext = processContext,
            operation = operation,
            toolGroups = toolGroups,
        )
    }
}
