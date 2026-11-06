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
package com.embabel.agent.a2a.multi

import com.embabel.agent.a2a.client.api.A2AClient
import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.domain.io.UserInput
import io.a2a.spec.Message
import io.a2a.spec.TextPart
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import java.util.UUID

// ---------------------------------------------------------------------------
// Leaf agents — echo requests back; each runs as a separate A2A endpoint.
// AgentOne and AgentTwo participate in the orchestration test (profile a2a-test).
// AgentFour is isolated to the baggage-chain test (profile a2a-multi-test) so
// it does not appear in the orchestrator's agent pool.
// ---------------------------------------------------------------------------

data class AgentOneResult(val reply: String)

@Agent(description = "AgentOne — processes requests and echoes them back")
@Profile("a2a-test")
class AgentOne {
    @Action
    @AchievesGoal(description = "AgentOne has processed the request")
    fun process(input: UserInput): AgentOneResult =
        AgentOneResult("AgentOne processed: ${input.content}")
}

data class AgentTwoResult(val reply: String)

@Agent(description = "AgentTwo — processes requests and transforms them")
@Profile("a2a-test")
class AgentTwo {
    @Action
    @AchievesGoal(description = "AgentTwo has processed the request")
    fun process(input: UserInput): AgentTwoResult =
        AgentTwoResult("AgentTwo processed: ${input.content}")
}

data class AgentFourResult(val reply: String)

@Agent(description = "AgentFour — leaf agent for baggage chain propagation test")
@Profile("a2a-multi-test")
class AgentFour {
    @Action
    @AchievesGoal(description = "AgentFour has processed the request")
    fun process(input: UserInput): AgentFourResult =
        AgentFourResult("AgentFour processed: ${input.content}")
}

// ---------------------------------------------------------------------------
// Orchestrator — delegates to AgentOne and AgentTwo via A2A, collecting their
// task responses so the test can assert contextId propagation.
// ---------------------------------------------------------------------------

data class OrchestratorResult(
    val contextId: String,
    val agentOneTaskId: String?,
    val agentTwoTaskId: String?,
    val agentOneContextId: String?,
    val agentTwoContextId: String?,
)

@Agent(description = "AgentOrchestrator — delegates to AgentOne and AgentTwo via A2A")
@Profile("a2a-test")
class AgentOrchestrator(
    private val a2aClient: A2AClient,
    private val environment: Environment,
) {
    // local.server.port is set via WebServerInitializedEvent after server start;
    // resolve lazily at action time, not at construction time
    private val port: Int get() = environment.getRequiredProperty("local.server.port", Int::class.java)
    private val agentOneBaseUrl: String get() = "http://localhost:$port/agent-one"
    private val agentTwoBaseUrl: String get() = "http://localhost:$port/agent-two"

    @Action
    @AchievesGoal(description = "AgentOrchestrator has delegated to AgentOne and AgentTwo")
    fun orchestrate(input: UserInput, context: OperationContext): OrchestratorResult {
        val contextId = context.agentProcess.id
        val message = Message.Builder()
            .role(Message.Role.USER)
            .messageId(UUID.randomUUID().toString())
            .contextId(contextId)
            .parts(listOf(TextPart(input.content)))
            .build()

        val taskOne = a2aClient.sendMessage(agentOneBaseUrl, message)
        val taskTwo = a2aClient.sendMessage(agentTwoBaseUrl, message)

        return OrchestratorResult(
            contextId = contextId,
            agentOneTaskId = taskOne.id,
            agentTwoTaskId = taskTwo.id,
            agentOneContextId = taskOne.contextId,
            agentTwoContextId = taskTwo.contextId,
        )
    }
}
