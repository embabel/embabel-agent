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
package com.embabel.agent.api.delay

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.channel.DevNullOutputChannel
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Delay
import com.embabel.agent.core.ProcessControl
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.expression.LogicalExpressionParser
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.spi.support.InMemoryAgentProcessRepository
import com.embabel.agent.spi.support.ProcessOptionsOperationScheduler
import com.embabel.agent.spi.support.SpringContextPlatformServices
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.DummyObjectCreatingLlmOperations
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.embabel.agent.core.Agent as CoreAgent

private const val ACTION_DELAY_MS = 200L
private const val TOLERANCE_MS = 50L

data class DelayStep1(val content: String)
data class DelayStep2(val content: String)

class AgentActionDelayTest {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        (LoggerFactory.getLogger(ProcessOptionsOperationScheduler::class.java)
                as ch.qos.logback.classic.Logger).level = ch.qos.logback.classic.Level.DEBUG
    }

    @AfterEach
    fun tearDown() {
        executor.shutdown()
    }

    @Agent(description = "agent with action-level delay")
    class AgentWithActionDelay {
        @Action
        fun step1(userInput: UserInput, context: ActionContext): DelayStep1 {
            context["step1End"] = System.currentTimeMillis()
            return DelayStep1(userInput.content)
        }

        @Action(delayMs = ACTION_DELAY_MS)
        @AchievesGoal(description = "final")
        fun step2(step: DelayStep1, context: ActionContext): DelayStep2 {
            context["step2Start"] = System.currentTimeMillis()
            return DelayStep2(step.content)
        }
    }

    @Agent(description = "agent with agent-level MEDIUM delay", delay = Delay.MEDIUM)
    class AgentWithAgentLevelDelay {
        @Action
        fun step1(userInput: UserInput, context: ActionContext): DelayStep1 {
            context["step1End"] = System.currentTimeMillis()
            return DelayStep1(userInput.content)
        }

        @Action
        @AchievesGoal(description = "final")
        fun step2(step: DelayStep1, context: ActionContext): DelayStep2 {
            context["step2Start"] = System.currentTimeMillis()
            return DelayStep2(step.content)
        }
    }

    @Agent(description = "action-level delay overrides agent-level LONG delay", delay = Delay.LONG)
    class AgentWithActionOverridingAgentDelay {
        @Action
        fun step1(userInput: UserInput, context: ActionContext): DelayStep1 {
            context["step1End"] = System.currentTimeMillis()
            return DelayStep1(userInput.content)
        }

        @Action(delayMs = ACTION_DELAY_MS)
        @AchievesGoal(description = "final")
        fun step2(step: DelayStep1, context: ActionContext): DelayStep2 {
            context["step2Start"] = System.currentTimeMillis()
            return DelayStep2(step.content)
        }
    }

    @Agent(description = "delayMs=0 suppresses process-level LONG operation delay")
    class AgentWithZeroDelayOverridingProcessDelay {
        @Action
        fun step1(userInput: UserInput, context: ActionContext): DelayStep1 {
            context["step1End"] = System.currentTimeMillis()
            return DelayStep1(userInput.content)
        }

        @Action(delayMs = 0L)
        @AchievesGoal(description = "final")
        fun step2(step: DelayStep1, context: ActionContext): DelayStep2 {
            context["step2Start"] = System.currentTimeMillis()
            return DelayStep2(step.content)
        }
    }

    @Agent(description = "delayMs=0 suppresses agent-level LONG delay", delay = Delay.LONG)
    class AgentWithZeroDelayOverridingAgentDelay {
        @Action
        fun step1(userInput: UserInput, context: ActionContext): DelayStep1 {
            context["step1End"] = System.currentTimeMillis()
            return DelayStep1(userInput.content)
        }

        @Action(delayMs = 0L)
        @AchievesGoal(description = "final")
        fun step2(step: DelayStep1, context: ActionContext): DelayStep2 {
            context["step2Start"] = System.currentTimeMillis()
            return DelayStep2(step.content)
        }
    }

    private fun blackboardFor(instance: Any, processOptions: ProcessOptions = ProcessOptions()): InMemoryBlackboard {
        val reader = AgentMetadataReader()
        val agent = reader.createAgentMetadata(instance) as CoreAgent
        val blackboard = InMemoryBlackboard()
        blackboard += UserInput("test")
        val platformServices = SpringContextPlatformServices(
            agentPlatform = dummyAgentPlatform(),
            llmOperations = DummyObjectCreatingLlmOperations.LoremIpsum,
            eventListener = EventSavingAgenticEventListener(),
            operationScheduler = ProcessOptionsOperationScheduler(),
            asyncer = ExecutorAsyncer(executor),
            embabelObjectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
            applicationContext = null,
            outputChannel = DevNullOutputChannel,
            templateRenderer = JinjavaTemplateRenderer(),
            customLogicalExpressionParser = LogicalExpressionParser.EMPTY,
            agentProcessRepository = InMemoryAgentProcessRepository(),
        )
        val process = SimpleAgentProcess(
            id = "test-${instance.javaClass.simpleName}",
            agent = agent,
            processOptions = processOptions,
            blackboard = blackboard,
            platformServices = platformServices,
            plannerFactory = DefaultPlannerFactory,
            parentId = null,
        )
        assertThat(process.run().status).isEqualTo(AgentProcessStatusCode.COMPLETED)
        return blackboard
    }

    private fun gap(blackboard: InMemoryBlackboard): Long =
        (blackboard["step2Start"] as Long) - (blackboard["step1End"] as Long)

    @Nested
    inner class ActionLevelDelay {

        @Test
        fun `action delayMs causes sleep before action body`() {
            assertThat(gap(blackboardFor(AgentWithActionDelay())))
                .`as`("gap should be at least ${ACTION_DELAY_MS}ms")
                .isGreaterThanOrEqualTo(ACTION_DELAY_MS - TOLERANCE_MS)
        }
    }

    @Nested
    inner class AgentLevelDelay {

        @Test
        fun `agent-level delay propagates to actions`() {
            assertThat(gap(blackboardFor(AgentWithAgentLevelDelay())))
                .`as`("gap should be at least ${Delay.MEDIUM.millis}ms")
                .isGreaterThanOrEqualTo(Delay.MEDIUM.millis - TOLERANCE_MS)
        }
    }

    @Nested
    inner class ActionOverridesProcessDelay {

        @Test
        fun `delayMs=0 suppresses process-level operation delay`() {
            val processOptions = ProcessOptions().withProcessControl(
                ProcessControl().withOperationDelay(Delay.LONG)
            )
            assertThat(gap(blackboardFor(AgentWithZeroDelayOverridingProcessDelay(), processOptions)))
                .`as`("explicit delayMs=0 must override process LONG operation delay — gap must be near zero")
                .isLessThan(TOLERANCE_MS)
        }
    }

    @Nested
    inner class ActionOverridesAgentDelay {

        @Test
        fun `action delayMs overrides agent-level delay`() {
            assertThat(gap(blackboardFor(AgentWithActionOverridingAgentDelay())))
                .`as`("action delay (${ACTION_DELAY_MS}ms) should apply, not agent-level LONG")
                .isGreaterThanOrEqualTo(ACTION_DELAY_MS - TOLERANCE_MS)
                .isLessThan(Delay.LONG.millis / 2)
        }

        @Test
        fun `delayMs=0 suppresses agent-level delay entirely`() {
            assertThat(gap(blackboardFor(AgentWithZeroDelayOverridingAgentDelay())))
                .`as`("explicit delayMs=0 must override agent LONG delay — gap must be near zero")
                .isLessThan(TOLERANCE_MS)
        }
    }
}
