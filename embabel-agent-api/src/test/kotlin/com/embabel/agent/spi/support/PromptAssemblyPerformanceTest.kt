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
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.SystemMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Prompt assembly — everything between the LLM operation being invoked and the provider
 * actually being called — is a fixed tax on every turn, paid before the model sees a token.
 * It was measured downstream at 240–420ms warm on a 40-tool chat surface, and nothing held
 * it, so it drifted unnoticed: from outside the call it is indistinguishable from model
 * latency.
 *
 * These tests pin it. The [FakeChatModel] returns instantly, so the measured window is
 * assembly ONLY — no network, no provider, no tool execution.
 *
 * Read the failure message before touching the budget. If assembly genuinely needs to get
 * slower, the number should move deliberately and with a reason, not because a run was
 * noisy — which is what the median-of-samples design below is for.
 */
class PromptAssemblyPerformanceTest {

    /**
     * Deliberately loose. CI boxes are shared and GC pauses are real, so a budget set just
     * above the true cost becomes a flaky test, and a flaky perf gate gets deleted rather
     * than fixed. This still sits far below the cost that motivated the work.
     */
    private val budget: Duration = 50.milliseconds

    /** Enough samples that one GC pause cannot move the median. */
    private val samples = 20

    /** Chat-sized tool surface. */
    private val toolCount = 40

    @Test
    fun `steady-state assembly of a large tool surface stays within budget`() {
        val fakeChatModel = FakeChatModel("fine, thanks")
        val setup = createChatClientLlmOperations(fakeChatModel)
        val tools = (1..toolCount).map { stubTool("tool_$it") }

        val call = { setup.assemble(tools) }

        // One-time init (template compilation, schema machinery, Spring AI advisor setup) is
        // a real cost but a DIFFERENT one. Conflating it with steady state turns this into a
        // startup budget and hides the per-turn regression it exists to catch.
        repeat(5) { call() }

        val timings = (1..samples).map { measureTime { call() } }.sorted()
        val median = timings[timings.size / 2]

        // Reported unconditionally: a green perf test that prints nothing tells you the
        // budget held, not how much headroom is left. The trend is the useful signal.
        logger.info(
            "prompt assembly, {} tools: median={} min={} max={} (budget {})",
            toolCount, median, timings.first(), timings.last(), budget,
        )

        assertThat(fakeChatModel.promptsPassed).isNotEmpty()
        assertThat(median)
            .describedAs(
                "steady-state prompt assembly for %d tools (median of %d; min=%s max=%s)",
                toolCount, samples, timings.first(), timings.last(),
            )
            .isLessThan(budget)
    }

    @Test
    fun `assembly does not scale linearly with tool count`() {
        val few = medianAssembly(toolCount = 4)
        val many = medianAssembly(toolCount = 40)
        logger.info("prompt assembly scaling: 4 tools={} vs 40 tools={}", few, many)

        // 10x the tools must not cost 10x. Per-tool work in the assembly path is the thing
        // this catches — it is what makes a rich tool surface expensive to OWN rather than
        // expensive to USE, and the user pays it on every turn regardless of their question.
        assertThat(many.inWholeMicroseconds)
            .describedAs("40 tools (%s) vs 4 tools (%s) — assembly should be near-flat", many, few)
            .isLessThan(few.inWholeMicroseconds * 4 + budget.inWholeMicroseconds)
    }

    private fun medianAssembly(toolCount: Int): Duration {
        val setup = createChatClientLlmOperations(FakeChatModel("fine, thanks"))
        val tools = (1..toolCount).map { stubTool("tool_$it") }
        val call = { setup.assemble(tools) }
        repeat(5) { call() }
        return (1..samples).map { measureTime { call() } }.sorted()[samples / 2]
    }

    /**
     * One assembly + call cycle with the given tool surface, shaped like a chat turn:
     * `String` output (so no structured-output schema is generated) and a system prompt plus
     * a short conversation.
     */
    private fun Setup.assemble(tools: List<Tool>) {
        llmOperations.createObject(
            messages = listOf(
                SystemMessage(SYSTEM_PROMPT),
                UserMessage("Hey. How's it going?"),
            ),
            interaction = LlmInteraction(
                id = InteractionId("perf"),
                llm = LlmOptions(),
                tools = tools,
            ),
            outputClass = String::class.java,
            action = SimpleTestAgent.actions.first(),
            agentProcess = mockAgentProcess,
        )
    }

    private fun stubTool(name: String): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = name,
            description = "Stub tool $name. " + "Description padding to approximate a real tool. ".repeat(4),
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result = Tool.Result.text("{}")
    }

    private data class Setup(
        val llmOperations: LlmOperations,
        val mockAgentProcess: AgentProcess,
    )

    private fun createChatClientLlmOperations(fakeChatModel: FakeChatModel): Setup {
        val ese = EventSavingAgenticEventListener()
        val mockProcessContext = mockk<ProcessContext>()
        every { mockProcessContext.platformServices } returns mockk()
        every { mockProcessContext.platformServices.agentPlatform } returns mockk()
        every { mockProcessContext.platformServices.agentPlatform.toolGroupResolver } returns
            RegistryToolGroupResolver("mt", emptyList())
        every { mockProcessContext.platformServices.eventListener } returns ese
        every { mockProcessContext.processOptions } returns ProcessOptions()

        val mockAgentProcess = mockk<AgentProcess>()
        every { mockAgentProcess.recordLlmInvocation(any()) } answers { }
        every { mockProcessContext.onProcessEvent(any()) } answers { ese.onProcessEvent(firstArg()) }
        every { mockProcessContext.agentProcess } returns mockAgentProcess
        every { mockAgentProcess.agent } returns SimpleTestAgent
        every { mockAgentProcess.processContext } returns mockProcessContext
        every { mockAgentProcess.blackboard } returns mockk(relaxed = true)

        val mockModelProvider = mockk<ModelProvider>()
        val crit = slot<ModelSelectionCriteria>()
        every { mockModelProvider.getLlm(capture(crit)) } returns
            SpringAiLlmService("fake", "provider", fakeChatModel, DefaultOptionsConverter)

        return Setup(
            llmOperations = ChatClientLlmOperations(
                modelProvider = mockModelProvider,
                toolDecorator = DefaultToolDecorator(),
                validator = Validation.buildDefaultValidatorFactory().validator,
                validationPromptGenerator = DefaultValidationPromptGenerator(),
                templateRenderer = JinjavaTemplateRenderer(),
                objectMapper = jacksonObjectMapper(),
                dataBindingProperties = LlmDataBindingProperties(),
                asyncer = ExecutorAsyncer(Executors.newCachedThreadPool()),
            ),
            mockAgentProcess = mockAgentProcess,
        )
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(PromptAssemblyPerformanceTest::class.java)

        /** Roughly the size of a real chat system prompt, so rendering isn't trivially cheap. */
        val SYSTEM_PROMPT = "You are a helpful assistant. ".repeat(200)
    }
}
