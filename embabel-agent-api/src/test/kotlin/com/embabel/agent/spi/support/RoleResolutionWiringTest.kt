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
@file:OptIn(InternalObservabilityApi::class)

package com.embabel.agent.spi.support

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.event.observation.InternalObservabilityApi
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.ConfigurableModelProvider
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider.Companion.CHEAPEST_ROLE
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Roles are resolved by [ConfigurableModelProvider], but the thing that makes a role able to
 * carry hyperparameters is [AbstractLlmOperations] asking it to, once per operation, before
 * anything reads the options. Unit-testing the provider alone would leave that wiring untested,
 * and its absence looks exactly like "the role's temperature is ignored".
 */
class RoleResolutionWiringTest {

    private data class Dog(val name: String)

    private fun llm(name: String, chatModel: FakeChatModel): LlmService<*> =
        SpringAiLlmService(name, "openai", chatModel, DefaultOptionsConverter)

    @Test
    fun `a by-role interaction reaches the model the role names, with the tuning it carries`() {
        val cheap = FakeChatModel(jacksonObjectMapper().writeValueAsString(Dog("Duke")))
        val expensive = FakeChatModel(jacksonObjectMapper().writeValueAsString(Dog("Rex")))

        val modelProvider = ConfigurableModelProvider(
            llms = listOf(llm("gpt-4.1-nano", cheap), llm("gpt-4.1-mini", expensive)),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(
                roles = mapOf(
                    CHEAPEST_ROLE to mapOf(
                        "openai" to LlmOptions.withModel("gpt-4.1-nano").withTemperature(0.2),
                    ),
                ),
                defaultLlm = "gpt-4.1-mini",
            ),
        )

        val (llmOperations, agentProcess) = setup(modelProvider)
        val dog = llmOperations.createObject(
            messages = listOf(UserMessage("Name a dog")),
            interaction = LlmInteraction(
                id = InteractionId("role"),
                llm = LlmOptions.withLlmForRole(CHEAPEST_ROLE),
            ),
            outputClass = Dog::class.java,
            action = SimpleTestAgent.actions.first(),
            agentProcess = agentProcess,
        )

        assertEquals(Dog("Duke"), dog, "the role's model answered, not the default")
        assertEquals(0, expensive.promptsPassed.size, "the default LLM must not have been called")
        assertEquals(0.2, cheap.optionsPassed.single().temperature, "the role's temperature travelled with it")
    }

    @Test
    fun `what the caller sets explicitly still beats the role`() {
        val cheap = FakeChatModel(jacksonObjectMapper().writeValueAsString(Dog("Duke")))
        val modelProvider = ConfigurableModelProvider(
            llms = listOf(llm("gpt-4.1-nano", cheap)),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(
                roles = mapOf(
                    CHEAPEST_ROLE to mapOf(
                        "openai" to LlmOptions.withModel("gpt-4.1-nano").withTemperature(0.2),
                    ),
                ),
                defaultLlm = "gpt-4.1-nano",
            ),
        )

        val (llmOperations, agentProcess) = setup(modelProvider)
        llmOperations.createObject(
            messages = listOf(UserMessage("Name a dog")),
            interaction = LlmInteraction(
                id = InteractionId("role"),
                llm = LlmOptions.withLlmForRole(CHEAPEST_ROLE).withTemperature(0.9),
            ),
            outputClass = Dog::class.java,
            action = SimpleTestAgent.actions.first(),
            agentProcess = agentProcess,
        )

        assertEquals(0.9, cheap.optionsPassed.single().temperature)
    }

    @Test
    fun `streaming gets the role's tuning too, not just the role's model`() {
        // Streaming is the chat path. Resolving only far enough to choose a model would leave the
        // streamer running on whatever the caller happened to pass, which is nothing.
        val streamed = slot<LlmOptions>()
        val roleModel = mockk<LlmService<*>>(relaxed = true)
        every { roleModel.name } returns "gpt-4.1-nano"
        every { roleModel.provider } returns "openai"
        every { roleModel.createMessageStreamer(capture(streamed)) } returns mockk(relaxed = true)

        val defaultModel = mockk<LlmService<*>>(relaxed = true)
        every { defaultModel.name } returns "gpt-4.1-mini"
        every { defaultModel.provider } returns "openai"

        val modelProvider = ConfigurableModelProvider(
            llms = listOf(roleModel, defaultModel),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(
                roles = mapOf(
                    CHEAPEST_ROLE to mapOf(
                        "openai" to LlmOptions.withModel("gpt-4.1-nano")
                            .withTemperature(0.2)
                            .withTimeout(Duration.ofSeconds(90)),
                    ),
                ),
                defaultLlm = "gpt-4.1-mini",
            ),
        )

        setup(modelProvider).llmOperations.createStreamingOperations(
            LlmOptions.withLlmForRole(CHEAPEST_ROLE),
        )

        assertEquals(0.2, streamed.captured.temperature)
        assertEquals(Duration.ofSeconds(90), streamed.captured.timeout)
        verify(exactly = 0) { defaultModel.createMessageStreamer(any()) }
    }

    @Test
    fun `capability queries answer about the model the role means, not the default`() {
        val roleModel = mockk<LlmService<*>>(relaxed = true)
        every { roleModel.name } returns "gpt-4.1-nano"
        every { roleModel.provider } returns "openai"
        every { roleModel.supportsStreaming() } returns true
        every { roleModel.supportsThinking() } returns true

        val defaultModel = mockk<LlmService<*>>(relaxed = true)
        every { defaultModel.name } returns "gpt-4.1-mini"
        every { defaultModel.provider } returns "openai"
        every { defaultModel.supportsStreaming() } returns false
        every { defaultModel.supportsThinking() } returns false

        val modelProvider = ConfigurableModelProvider(
            llms = listOf(roleModel, defaultModel),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(
                roles = mapOf(CHEAPEST_ROLE to mapOf("openai" to LlmOptions.withModel("gpt-4.1-nano"))),
                defaultLlm = "gpt-4.1-mini",
            ),
        )

        val ops = setup(modelProvider).llmOperations
        val asRole = LlmOptions.withLlmForRole(CHEAPEST_ROLE)

        // The two models disagree, so answering from the default would be visible here.
        assertTrue(ops.supportsStreaming(asRole))
        assertTrue(ops.supportsThinking(asRole))
    }

    /**
     * The same guarantee on the OTHER implementation.
     *
     * [ToolLoopLlmOperations] has its own low-level transform path, and role resolution was missing
     * from it - the defect this PR fixes. Every other case here drives [ChatClientLlmOperations],
     * so all of them would stay green with the tool-loop path unresolved, and a deployment using it
     * would silently run every by-role call on `default-llm`.
     */
    @Test
    fun `the tool loop path resolves the role too, model and tuning`() {
        val cheap = FakeChatModel(jacksonObjectMapper().writeValueAsString(Dog("Duke")))
        val expensive = FakeChatModel(jacksonObjectMapper().writeValueAsString(Dog("Rex")))

        val modelProvider = ConfigurableModelProvider(
            llms = listOf(llm("gpt-4.1-nano", cheap), llm("gpt-4.1-mini", expensive)),
            embeddingServices = emptyList(),
            properties = ConfigurableModelProviderProperties(
                roles = mapOf(
                    CHEAPEST_ROLE to mapOf(
                        "openai" to LlmOptions.withModel("gpt-4.1-nano").withTemperature(0.2),
                    ),
                ),
                defaultLlm = "gpt-4.1-mini",
            ),
        )

        val (_, agentProcess) = setup(modelProvider)
        val toolLoopOperations = ToolLoopLlmOperations(
            modelProvider = modelProvider,
            toolDecorator = DefaultToolDecorator(),
            validator = Validation.buildDefaultValidatorFactory().validator,
            validationPromptGenerator = DefaultValidationPromptGenerator(),
            dataBindingProperties = LlmDataBindingProperties(),
            objectMapper = jacksonObjectMapper(),
            asyncer = ExecutorAsyncer(Executors.newCachedThreadPool()),
            templateRenderer = JinjavaTemplateRenderer(),
        )

        // String output on purpose. The tool loop takes its own structured-output route, and
        // binding a data class here would make this depend on that rather than on role resolution,
        // which is the thing under test - and would fail for a reason that has nothing to do with
        // whether the role was resolved.
        toolLoopOperations.createObject(
            messages = listOf(UserMessage("Name a dog")),
            interaction = LlmInteraction(
                id = InteractionId("role"),
                llm = LlmOptions.withLlmForRole(CHEAPEST_ROLE),
            ),
            outputClass = String::class.java,
            action = SimpleTestAgent.actions.first(),
            agentProcess = agentProcess,
        )

        assertEquals(1, cheap.promptsPassed.size, "the role's model answered")
        assertEquals(0, expensive.promptsPassed.size, "the default LLM must not have been called")
        assertEquals(0.2, cheap.optionsPassed.single().temperature, "the role's temperature travelled with it")
    }

    private data class Wiring(
        val llmOperations: ChatClientLlmOperations,
        val agentProcess: AgentProcess,
    )

    private fun setup(modelProvider: ConfigurableModelProvider): Wiring {
        val ese = EventSavingAgenticEventListener()
        val processContext = mockk<ProcessContext>()
        every { processContext.platformServices } returns mockk()
        every { processContext.platformServices.agentPlatform } returns mockk()
        every { processContext.platformServices.agentPlatform.toolGroupResolver } returns
            RegistryToolGroupResolver("mt", emptyList())
        every { processContext.platformServices.eventListener } returns ese
        every { processContext.processOptions } returns ProcessOptions()

        val agentProcess = mockk<AgentProcess>()
        every { agentProcess.recordLlmInvocation(any()) } answers { }
        every { processContext.onProcessEvent(any()) } answers { ese.onProcessEvent(firstArg()) }
        every { processContext.agentProcess } returns agentProcess
        every { agentProcess.agent } returns SimpleTestAgent
        every { agentProcess.processContext } returns processContext
        every { agentProcess.blackboard } returns mockk(relaxed = true)

        return Wiring(
            ChatClientLlmOperations(
                modelProvider = modelProvider,
                toolDecorator = DefaultToolDecorator(),
                validator = Validation.buildDefaultValidatorFactory().validator,
                validationPromptGenerator = DefaultValidationPromptGenerator(),
                templateRenderer = JinjavaTemplateRenderer(),
                embabelObjectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
                dataBindingProperties = LlmDataBindingProperties(),
                asyncer = ExecutorAsyncer(Executors.newCachedThreadPool()),
            ),
            agentProcess,
        )
    }
}
