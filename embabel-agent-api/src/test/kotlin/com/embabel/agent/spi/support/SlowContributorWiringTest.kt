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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.event.observation.InternalObservabilityApi
import com.embabel.agent.api.reference.LlmReference
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

/**
 * The unit test for the slow-contributor warning exercises
 * `buildPromptContributionsString` directly. This one proves the WIRING: that a contributor
 * supplied on an [LlmInteraction] actually reaches that function during a real
 * `createObject` call, and that a reference reports its own name rather than a class name.
 *
 * Worth its own test because the value of the warning depends entirely on the wiring. A
 * correct implementation that never runs on the real path, or that names a decorator class
 * instead of the reference, would have been useless for the case that motivated it — an
 * application's eager memory retrieval adding ~330ms to every turn.
 */
class SlowContributorWiringTest {

    private val contributionsLogger = LoggerFactory.getLogger(
        "com.embabel.agent.spi.support.PromptContributions",
    ) as ch.qos.logback.classic.Logger

    /**
     * Shared with every other test in the JVM: warn-once state is deliberately process-global.
     */
    @BeforeEach
    fun resetWarnedContributors() {
        warnedSlowContributors.clear()
    }

    @Test
    fun `a slow reference is reported by name during a real LLM call`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        contributionsLogger.addAppender(appender)
        try {
            // Named uniquely per run: the warning fires once per contributor for the life of
            // the JVM, so a fixed name would pass or fail depending on test ordering.
            val referenceName = "slow-memory-${System.nanoTime()}"
            val slowReference = object : LlmReference {
                override val name = referenceName
                override val description = "a reference that does I/O in contribution()"
                override fun notes(): String = ""
                override fun contribution(): String {
                    Thread.sleep(300)
                    return "recalled something"
                }

                override fun tools(): List<Tool> = emptyList()
            }

            createOperations().createObject(
                messages = listOf(UserMessage("Hey. How's it going?")),
                interaction = LlmInteraction(
                    id = InteractionId("wiring"),
                    llm = LlmOptions(),
                    promptContributors = listOf(slowReference),
                ),
                outputClass = String::class.java,
                action = SimpleTestAgent.actions.first(),
                agentProcess = mockAgentProcess,
            )

            val warnings = appender.list.filter { it.level == Level.WARN }
            assertThat(warnings).hasSize(1)
            assertThat(warnings.single().formattedMessage)
                .describedAs("the warning must identify the reference, not a wrapper class")
                .contains(referenceName)
        } finally {
            contributionsLogger.detachAppender(appender)
        }
    }

    private lateinit var mockAgentProcess: AgentProcess

    private fun createOperations(): ChatClientLlmOperations {
        val ese = EventSavingAgenticEventListener()
        val mockProcessContext = mockk<ProcessContext>()
        every { mockProcessContext.platformServices } returns mockk()
        every { mockProcessContext.platformServices.agentPlatform } returns mockk()
        every { mockProcessContext.platformServices.agentPlatform.toolGroupResolver } returns
            RegistryToolGroupResolver("mt", emptyList())
        every { mockProcessContext.platformServices.eventListener } returns ese
        every { mockProcessContext.processOptions } returns ProcessOptions()

        mockAgentProcess = mockk<AgentProcess>()
        every { mockAgentProcess.recordLlmInvocation(any()) } answers { }
        every { mockProcessContext.onProcessEvent(any()) } answers { ese.onProcessEvent(firstArg()) }
        every { mockProcessContext.agentProcess } returns mockAgentProcess
        every { mockAgentProcess.agent } returns SimpleTestAgent
        every { mockAgentProcess.processContext } returns mockProcessContext
        every { mockAgentProcess.blackboard } returns mockk(relaxed = true)

        val mockModelProvider = mockk<ModelProvider>()
        val crit = slot<ModelSelectionCriteria>()
        every { mockModelProvider.getLlm(capture(crit)) } returns
            SpringAiLlmService("fake", "provider", FakeChatModel("fine, thanks"), DefaultOptionsConverter)

        return ChatClientLlmOperations(
            modelProvider = mockModelProvider,
            toolDecorator = DefaultToolDecorator(),
            validator = Validation.buildDefaultValidatorFactory().validator,
            validationPromptGenerator = DefaultValidationPromptGenerator(),
            templateRenderer = JinjavaTemplateRenderer(),
            embabelObjectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
            dataBindingProperties = LlmDataBindingProperties(),
            asyncer = ExecutorAsyncer(Executors.newCachedThreadPool()),
        )
    }
}
