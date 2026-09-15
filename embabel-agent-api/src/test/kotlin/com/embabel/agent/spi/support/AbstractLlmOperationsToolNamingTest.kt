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
package com.embabel.agent.spi.support

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.Action
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ToolNamingStrategy
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.AutoLlmSelectionCriteriaResolver
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.ToolDecorator
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.core.thinking.ThinkingResponse
import io.mockk.every
import io.mockk.mockk
import jakarta.validation.Validation
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.assertEquals

/**
 * Verifies that the common LLM operations boundary applies platform tool naming.
 */
class AbstractLlmOperationsToolNamingTest {

    @Nested
    inner class ObjectPaths {

        @Test
        fun `resolves tool names from the process agent and action on every object path`() {
            val fixture = fixture()
            val interaction = LlmInteraction(
                id = InteractionId("test"),
                tools = listOf(tool("search")),
            )
            val messages = listOf(UserMessage("search"))

            fixture.operations.createObject(
                messages = messages,
                interaction = interaction,
                outputClass = String::class.java,
                agentProcess = fixture.agentProcess,
                action = fixture.action,
            )
            assertNamed(fixture.operations)

            fixture.operations.createObjectIfPossible(
                messages = messages,
                interaction = interaction,
                outputClass = String::class.java,
                agentProcess = fixture.agentProcess,
                action = fixture.action,
            )
            assertNamed(fixture.operations)

            fixture.operations.createObjectWithThinking(
                messages = messages,
                interaction = interaction,
                outputClass = String::class.java,
                agentProcess = fixture.agentProcess,
                action = fixture.action,
            )
            assertNamed(fixture.operations)

            fixture.operations.createObjectIfPossibleWithThinking(
                messages = messages,
                interaction = interaction,
                outputClass = String::class.java,
                agentProcess = fixture.agentProcess,
                action = fixture.action,
            )
            assertNamed(fixture.operations)
        }
    }

    @Nested
    inner class InjectedTools {

        @Test
        fun `names tools injected after initial resolution`() {
            val fixture = fixture()
            val context = ToolNamingContext.forLlmCall(
                toolConsumer = LlmInteraction(id = InteractionId("test")),
                agentProcess = fixture.agentProcess,
            )

            val name = context.name(tool("search")).definition.name

            assertEquals("Agent-search", name)
        }

        @Test
        fun `the shared injection decorator qualifies before decorating`() {
            val fixture = fixture()
            val seenByDecorator = mutableListOf<String>()
            val decorator = mockk<ToolDecorator>()
            every { decorator.decorate(any(), any(), any(), any()) } answers {
                firstArg<Tool>().also { seenByDecorator += it.definition.name }
            }

            val decorate = ToolNamingContext.qualifyingToolDecorator(
                toolConsumer = LlmInteraction(id = InteractionId("test")),
                agentProcess = fixture.agentProcess,
                action = fixture.action,
                llmOptions = LlmOptions(),
                toolDecorator = decorator,
            )

            val decorated = decorate(tool("search"))

            assertEquals("Agent-search", decorated.definition.name)
            assertEquals(listOf("Agent-search"), seenByDecorator)
        }
    }

    @Nested
    inner class Idempotence {

        @Test
        fun `qualifying an already qualified tool is a no-op`() {
            val fixture = fixture()
            val context = ToolNamingContext.forLlmCall(
                toolConsumer = LlmInteraction(id = InteractionId("test")),
                agentProcess = fixture.agentProcess,
            )

            val once = context.name(tool("search"))
            val twice = context.name(once)

            assertEquals("Agent-search", once.definition.name)
            assertEquals(once.definition.name, twice.definition.name)
        }

        @Test
        fun `qualifying a qualified tool wrapped by another decorator is a no-op`() {
            val fixture = fixture()
            val context = ToolNamingContext.forLlmCall(
                toolConsumer = LlmInteraction(id = InteractionId("test")),
                agentProcess = fixture.agentProcess,
            )
            val wrapped = context.name(tool("search")).withDescription("wrapped")

            val again = context.name(wrapped)

            assertEquals("Agent-search", again.definition.name)
        }
    }

    private fun assertNamed(operations: TestableAbstractLlmOperations) {
        assertEquals(listOf("Agent-search"), operations.transformedInteraction.tools.map { it.definition.name })
    }

    private fun fixture(): Fixture {
        val agent = mockk<Agent>()
        every { agent.name } returns "Agent"
        val action = mockk<Action>()
        every { action.shortName() } returns "run"
        val processContext = mockk<ProcessContext>(relaxed = true)
        val platformServices = processContext.platformServices
        val agentPlatform = mockk<AgentPlatform>(relaxed = true)
        every { platformServices.agentPlatform } returns agentPlatform
        every { platformServices.toolNamingStrategy() } returns ToolNamingStrategy.FULLY_QUALIFIED
        every { agentPlatform.toolGroupResolver } returns RegistryToolGroupResolver("test", emptyList())
        val agentProcess = mockk<AgentProcess>(relaxed = true)
        every { agentProcess.agent } returns agent
        every { agentProcess.processContext } returns processContext
        val decorator = mockk<ToolDecorator>()
        every { decorator.decorate(any(), any(), any(), any()) } answers { firstArg() }
        val modelProvider = mockk<ModelProvider>()
        val llmService = mockk<LlmService<*>>(relaxed = true)
        every { modelProvider.getLlm(any<ModelSelectionCriteria>()) } returns llmService
        every { llmService.name } returns "test-model"
        every { llmService.provider } returns "test-provider"
        return Fixture(
            operations = TestableAbstractLlmOperations(
                toolDecorator = decorator,
                modelProvider = modelProvider,
                objectMapper = jacksonObjectMapper(),
                asyncer = DirectAsyncer,
            ),
            agentProcess = agentProcess,
            action = action,
        )
    }

    private data class Fixture(
        val operations: TestableAbstractLlmOperations,
        val agentProcess: AgentProcess,
        val action: Action,
    )

    private class TestableAbstractLlmOperations(
        toolDecorator: ToolDecorator,
        modelProvider: ModelProvider,
        objectMapper: ObjectMapper,
        asyncer: Asyncer,
    ) : AbstractLlmOperations(
        toolDecorator = toolDecorator,
        modelProvider = modelProvider,
        validator = Validation.buildDefaultValidatorFactory().validator,
        autoLlmSelectionCriteriaResolver = AutoLlmSelectionCriteriaResolver.DEFAULT,
        dataBindingProperties = LlmDataBindingProperties(),
        asyncer = asyncer,
        objectMapper = objectMapper,
    ) {
        lateinit var transformedInteraction: LlmInteraction

        override fun <O> doTransform(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?,
        ): O {
            transformedInteraction = interaction
            @Suppress("UNCHECKED_CAST")
            return "ok" as O
        }

        override fun <O> doTransformIfPossible(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>,
        ): Result<O> = Result.success(transform(interaction))

        override fun <O> doTransformWithThinking(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?,
        ): ThinkingResponse<O> = ThinkingResponse(transform(interaction), emptyList())

        override fun <O> doTransformWithThinkingIfPossible(
            messages: List<Message>,
            interaction: LlmInteraction,
            outputClass: Class<O>,
            llmRequestEvent: LlmRequestEvent<O>?,
        ): Result<ThinkingResponse<O>> = Result.success(
            ThinkingResponse(transform(interaction), emptyList())
        )

        private fun <O> transform(interaction: LlmInteraction): O {
            transformedInteraction = interaction
            @Suppress("UNCHECKED_CAST")
            return "ok" as O
        }
    }

    private object DirectAsyncer : Asyncer {
        override fun <T> async(block: () -> T): CompletableFuture<T> =
            CompletableFuture.completedFuture(block())

        override fun <T, R> parallelMap(items: Collection<T>, maxConcurrency: Int, transform: (T) -> R): List<R> =
            items.map(transform)
    }

    private fun tool(name: String): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = name,
            description = name,
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result = error("not used")
    }
}
