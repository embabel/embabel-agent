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
package com.embabel.agent.e2e

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.channel.InformativeOutputChannelEvent
import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.channel.OutputChannelEvent
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.event.progress.OutputChannelHighlightingEventListener
import com.embabel.agent.api.tool.Subagent
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals

@Agent(description = "Parent agent")
class ParentAgentWithToolishSubagentTest {

    private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Action
    fun start(
        input: UserInput,
        operationContext: OperationContext,
    ): SubagentTestResult {
        operationContext.agentProcess.processOptions.listeners.forEach {
            it.onProcessEvent(
                mockk<LlmRequestEvent<*>> {
                    every { processId } returns operationContext.agentProcess.id
                    every { llmMetadata.name } returns "ParentAgentWithToolishSubagentTest"
                }
            )
        }

        // subagent as a tool-calling imitation
        val subagentAsTool = Subagent.ofAnnotatedInstance(SubAgentTest()).consuming(UserInput::class)
        val toolResult = subagentAsTool.call(UserInput("message").let(mapper::writeValueAsString))
        val toolResultContent: String = when (toolResult) {
            is Tool.Result.Error -> throw toolResult.cause ?: IllegalStateException("SubAgent as a tool has thrown an error")
            is Tool.Result.Text -> toolResult.content
            is Tool.Result.WithArtifact -> toolResult.content
        }

        return if (toolResultContent != "SubagentTestResult(message=Sub-agent result)") {
            throw IllegalStateException("Tool result is unexpected for testing")
        } else {
            SubagentTestResult("Sub-agent result")
        }
    }

    @Action
    @AchievesGoal(description = "All stages complete")
    fun done(taskOutput: SubagentTestResult): SubagentTestResult = taskOutput
}

data class SubagentTestResult(val message: String)

@Agent(description = "SubAgentTest")
class SubAgentTest {

    @Action
    @AchievesGoal(description = "Delivers sub-agent result")
    fun deliver(
        input: UserInput,
        operationContext: OperationContext
    ): SubagentTestResult {
        operationContext.agentProcess.processOptions.listeners.forEach {
            it.onProcessEvent(
                mockk<LlmRequestEvent<*>> {
                    every { processId } returns operationContext.agentProcess.id
                    every { llmMetadata.name } returns "SubAgentTest"
                }
            )
        }
        return SubagentTestResult("Sub-agent result")
    }
}


@SpringBootTest
@ActiveProfiles("test")
@Import(
    value = [
        FakeConfig::class,
    ],
)
class SubagentIntegrationTest(
    @param:Autowired
    private val agentPlatform: AgentPlatform,
    @param:Autowired
    private val agentMetadataReader: AgentMetadataReader,
) {
    private val outerAgentViaSubagentToolInvocation =
        agentMetadataReader
            .createAgentMetadata(ParentAgentWithToolishSubagentTest())!!
            .createAgent(ParentAgentWithToolishSubagentTest::class.simpleName!!, "", "")

    private val subAgent =
        agentMetadataReader
            .createAgentMetadata(SubAgentTest())!!
            .createAgent(SubAgentTest::class.simpleName!!, "", "")

    @BeforeEach
    fun setup() {
        agentPlatform.deploy(outerAgentViaSubagentToolInvocation)
        agentPlatform.deploy(subAgent)
    }

    @Test
    fun `outer agent as a parent propagates its process listeners to a toolish subagent as expected`() {
        val agentEvents = ConcurrentLinkedQueue<String>()
        val parentAgentListeners = listOf(
            OutputChannelHighlightingEventListener(
                outputChannel = object : OutputChannel {
                    override fun send(event: OutputChannelEvent) {
                        when (event) {
                            is InformativeOutputChannelEvent -> agentEvents.add(event.message)
                        }
                    }
                },
                verbose = true,
            )
        )

        val agentProcess = agentPlatform.createAgentProcess(
            agent = outerAgentViaSubagentToolInvocation,
            processOptions =
                ProcessOptions(
                    listeners = parentAgentListeners,
                ),
            bindings = mapOf("it" to UserInput("test")),
        )

        agentProcess.run()
        assertEquals(AgentProcessStatusCode.COMPLETED, agentProcess.status)

        assertEquals(
            listOf(
                "Calling LLM ParentAgentWithToolishSubagentTest",
                "Calling LLM SubAgentTest"
            ),
            agentEvents.toList(),
            "Expected that AgentProcess listeners are reachable from sub-agents"
        )
    }
}
