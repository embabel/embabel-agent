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

import com.embabel.agent.AgentTestApplication
import com.embabel.agent.a2a.A2A_CONTEXT_ID_KEY
import com.embabel.agent.a2a.client.EmbabelA2AClient
import com.embabel.agent.a2a.client.api.A2AClient
import com.embabel.agent.a2a.server.config.FakeAiConfiguration
import com.embabel.agent.a2a.server.config.FakeRankerConfiguration
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.invocation.AgentInvocation
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.domain.io.UserInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import io.a2a.spec.Message
import io.a2a.spec.TextPart
import io.opentelemetry.context.Context
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Full-stack integration test for A2A contextId propagation across multiple agents.
 *
 * ## Test topology
 * A real embedded Tomcat starts on a random port. Three agents are exposed as A2A server
 * endpoints via [MultiAgentTestConfiguration]:
 * - `/agent-one` — routes to [AgentOne], goal filter matches "AgentOne"
 * - `/agent-two` — routes to [AgentTwo], goal filter matches "AgentTwo"
 * - `/agent-four` — routes to [AgentFour], isolated behind `a2a-multi-test` profile so it
 *                    does not appear in the orchestrator's agent pool
 *
 * ## Why the default ranker is replaced
 * [FakeRankerConfiguration] (active on `a2a-test`) returns a single fixed agent, which
 * breaks multi-agent dispatch. [MultiAgentRankerConfiguration] overrides it with a ranker
 * that assigns a uniform 0.9 score to all candidates so the orchestrator can route to any
 * registered agent.
 *
 * ## `orchestrator propagates contextId` test
 * [AgentOrchestrator] is invoked directly via [AgentInvocation] (not over A2A). Its action
 * sets `message.contextId` to `context.agentProcess.id`, then calls [AgentOne] and [AgentTwo]
 * over A2A using [EmbabelA2AClient]. The A2A server preserves contextId in the returned
 * [io.a2a.spec.Task]. The test asserts that both downstream tasks echo back the same
 * contextId that the orchestrator set.
 *
 * ## [ContextKeyChainPropagation] nested class
 * Tests the OTel [io.opentelemetry.context.ContextKey] path: when [EmbabelA2AClient] sends a
 * message with no explicit contextId, it reads the session identifier from [A2A_CONTEXT_ID_KEY]
 * and injects it into the outbound message body. The receiving [AgentFour] server returns a
 * [io.a2a.spec.Task] whose `contextId` field reflects what it received — confirming the session
 * identifier was propagated correctly across the hop. The test sets [A2A_CONTEXT_ID_KEY] manually
 * to simulate what [com.embabel.agent.a2a.server.support.AutonomyA2ARequestHandler] does when
 * processing an inbound A2A request. [AgentFour] is isolated behind the `a2a-multi-test` profile.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [AgentTestApplication::class],
)
@ActiveProfiles("test", "a2a-test", "a2a-multi-test")
@EnableAutoConfiguration
@ComponentScan(
    basePackages = [
        "com.embabel.agent.a2a.client",
        "com.embabel.agent.a2a.server",
        "com.embabel.agent.a2a.multi",
    ],
    excludeFilters = [ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = [FakeRankerConfiguration::class],
    )],
)
@Import(
    FakeAiConfiguration::class,
    MultiAgentRankerConfiguration::class,
    MultiAgentTestConfiguration::class,
)
class A2AMultiAgentIntegrationTest(
    @Autowired private val agentPlatform: AgentPlatform,
    @Autowired private val agentOne: AgentOne,
    @Autowired private val agentTwo: AgentTwo,
    @Autowired private val orchestrator: AgentOrchestrator,
) {

    @BeforeEach
    fun deployAgents() {
        AgentMetadataReader().createAgentScopes(agentOne).forEach { agentPlatform.deploy(it) }
        AgentMetadataReader().createAgentScopes(agentTwo).forEach { agentPlatform.deploy(it) }
        AgentMetadataReader().createAgentScopes(orchestrator).forEach { agentPlatform.deploy(it) }
    }

    @Test
    fun `orchestrator propagates contextId to AgentOne and AgentTwo`() {
        val process = AgentInvocation.create(agentPlatform, OrchestratorResult::class.java)
            .runAsync(UserInput("Hello from integration test"))
            .join()

        assertEquals(AgentProcessStatusCode.COMPLETED, process.status)

        val result = process.last(OrchestratorResult::class.java)
        assertNotNull(result)

        assertEquals(
            result!!.contextId,
            result.agentOneContextId,
            "AgentOne should receive the orchestrator's contextId",
        )
        assertEquals(
            result.contextId,
            result.agentTwoContextId,
            "AgentTwo should receive the orchestrator's contextId",
        )
        assertEquals(
            result.agentOneContextId,
            result.agentTwoContextId,
            "AgentOne and AgentTwo should share the same contextId",
        )
    }

    /**
     * Verifies transparent contextId chain propagation via [A2A_CONTEXT_ID_KEY].
     *
     * [A2A_CONTEXT_ID_KEY] is set manually here to simulate what
     * [com.embabel.agent.a2a.server.support.AutonomyA2ARequestHandler] does when it receives
     * an inbound A2A request: it stores the inbound contextId in the key so any outbound A2A
     * call made by the agent during that request automatically inherits the same session ID.
     */
    @Nested
    inner class ContextKeyChainPropagation {

        @Autowired
        private lateinit var agentFour: AgentFour

        @Autowired
        private lateinit var a2aClient: A2AClient

        @LocalServerPort
        private val port: Int = 0

        @BeforeEach
        fun deployAgentFour() {
            AgentMetadataReader().createAgentScopes(agentFour).forEach { agentPlatform.deploy(it) }
        }

        @Test
        fun `EmbabelA2AClient injects OTel context key contextId into outbound message when message has none`() {
            val contextId = "ctx-key-${UUID.randomUUID()}"

            // Simulate what AutonomyA2ARequestHandler does on the server thread when it
            // receives an inbound A2A request: store the session contextId in the JVM-local
            // OTel context key so EmbabelA2AClient can read it without agent code having to
            // thread it explicitly.
            val scope = Context.current().with(A2A_CONTEXT_ID_KEY, contextId).makeCurrent()

            scope.use {
                // Message carries NO contextId — EmbabelA2AClient must read it from the key.
                val message = Message.Builder()
                    .role(Message.Role.USER)
                    .messageId(UUID.randomUUID().toString())
                    .parts(listOf(TextPart("context key chain test")))
                    .build()

                val task = a2aClient.sendMessage("http://localhost:$port/agent-four", message)

                // The server echoes the contextId back in the task. If EmbabelA2AClient had
                // not injected it, the server would have generated a new random one instead.
                assertEquals(
                    contextId,
                    task.contextId,
                    "Server must receive the contextId that EmbabelA2AClient read from the OTel context key",
                )
            }
        }

        @Test
        fun `EmbabelA2AClient does not override message contextId when context key is absent`() {
            val messageContextId = "msg-ctx-${UUID.randomUUID()}"

            // No A2A_CONTEXT_ID_KEY set — bare context, simulating a direct client call
            // not originating from within an A2A server request.
            val message = Message.Builder()
                .role(Message.Role.USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(messageContextId)
                .parts(listOf(TextPart("no context key test")))
                .build()

            val task = a2aClient.sendMessage("http://localhost:$port/agent-four", message)

            assertEquals(
                messageContextId,
                task.contextId,
                "Message's own contextId must be used when no OTel context key is set",
            )
        }

        @Test
        fun `server generates its own contextId when neither context key nor message contextId is set`() {
            // No key set, no message contextId — server generates a random one.
            val message = Message.Builder()
                .role(Message.Role.USER)
                .messageId(UUID.randomUUID().toString())
                .parts(listOf(TextPart("no context id test")))
                .build()

            val task = a2aClient.sendMessage("http://localhost:$port/agent-four", message)

            assertNotNull(task.contextId, "Server must always assign a contextId")
            assertNotEquals("", task.contextId)
        }
    }
}
