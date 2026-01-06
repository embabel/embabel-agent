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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Domain types for trigger testing
 */
data class UserMessage(val content: String)
data class SystemContext(val sessionId: String)
data class Response(val reply: String)

data class EventA(val data: String)
data class EventB(val data: String)
data class ProcessedResult(val source: String, val processed: String)

/**
 * Domain types for null-returning trigger test
 */
data class IncomingRequest(val id: String)
data class RequestContext(val contextId: String)

/**
 * Agent that uses trigger to react only when UserMessage is the last result.
 * Both UserMessage and SystemContext must be present, but the action only fires
 * when UserMessage was just added.
 */
@Agent(description = "Chat agent with trigger-based message handling")
class TriggerChatAgent {

    @AchievesGoal(description = "Respond to user message")
    @Action(trigger = UserMessage::class)
    fun handleMessage(
        userMessage: UserMessage,
        systemContext: SystemContext
    ): Response {
        return Response("Session ${systemContext.sessionId}: Received '${userMessage.content}'")
    }
}

/**
 * Agent without trigger - action fires as soon as both parameters are available,
 * regardless of which was added last.
 */
@Agent(description = "Chat agent without trigger")
class NonTriggerChatAgent {

    @AchievesGoal(description = "Respond to user message")
    @Action
    fun handleMessage(
        userMessage: UserMessage,
        systemContext: SystemContext
    ): Response {
        return Response("Session ${systemContext.sessionId}: Received '${userMessage.content}'")
    }
}

/**
 * Agent with multiple potential triggers - demonstrates selective reaction.
 */
@Agent(description = "Multi-event processor")
class MultiEventAgent {

    @Action(trigger = EventA::class)
    fun processEventA(
        eventA: EventA,
        eventB: EventB
    ): ProcessedResult {
        return ProcessedResult("A", "Processed A: ${eventA.data} with B: ${eventB.data}")
    }

    @AchievesGoal(description = "Process event B")
    @Action(trigger = EventB::class)
    fun processEventB(
        eventA: EventA,
        eventB: EventB
    ): ProcessedResult {
        return ProcessedResult("B", "Processed B: ${eventB.data} with A: ${eventA.data}")
    }
}

/**
 * Agent that demonstrates the fix for trigger with canRerun=true and null return.
 * Previously, when the action returned null, the lastResult didn't change, so the trigger
 * precondition remained satisfied, causing an infinite loop.
 * Now, ActionVoidResult is added to the blackboard to invalidate the trigger.
 */
@Agent(description = "Agent with null-returning trigger action")
class NullReturningTriggerAgent {

    var invocationCount = 0

    @AchievesGoal(description = "Process request")
    @Action(trigger = IncomingRequest::class, canRerun = true)
    fun processRequest(
        request: IncomingRequest,
        context: RequestContext
    ): Unit {
        invocationCount++
        // Returns Unit (void) - ActionVoidResult added to blackboard
        // lastResult becomes ActionVoidResult, not IncomingRequest
        // trigger precondition lastResult:IncomingRequest becomes FALSE
        // action does not rerun
        println("processRequest invoked: count=$invocationCount")
    }
}

class TriggerAnnotationTest {

    private val reader = AgentMetadataReader()

    @Nested
    inner class BasicTriggerBehavior {

        @Test
        fun `action with trigger fires when trigger type is last result`() {
            val agent = reader.createAgentMetadata(TriggerChatAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            // Add SystemContext first, then UserMessage (trigger)
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                linkedMapOf(
                    "systemContext" to SystemContext("session-123"),
                    "it" to UserMessage("Hello!")
                )
            )

            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            val result = process.getValue("it", Response::class.java.name) as? Response
            assertNotNull(result)
            assertTrue(result!!.reply.contains("Hello!"))
            assertTrue(result.reply.contains("session-123"))
        }

        @Test
        fun `action without trigger fires regardless of parameter order`() {
            val agent = reader.createAgentMetadata(NonTriggerChatAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            // Add UserMessage first, then SystemContext
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf(
                    "userMessage" to UserMessage("Hello!"),
                    "it" to SystemContext("session-456")
                )
            )

            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            val result = process.getValue("it", Response::class.java.name) as? Response
            assertNotNull(result)
            assertTrue(result!!.reply.contains("Hello!"))
        }
    }

    @Nested
    inner class MultiEventTrigger {

        @Test
        fun `correct action fires based on which event is last result`() {
            val agent = reader.createAgentMetadata(MultiEventAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            // Add EventA first, then EventB (trigger for processEventB)
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf(
                    "eventA" to EventA("dataA"),
                    "it" to EventB("dataB")
                )
            )

            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            val result = process.getValue("it", ProcessedResult::class.java.name) as? ProcessedResult
            assertNotNull(result)
            // Should have processed via processEventB since EventB was the trigger
            assertEquals("B", result!!.source)
            assertTrue(result.processed.contains("Processed B"))
        }
    }

    @Nested
    inner class MetadataReading {

        @Test
        fun `agent with trigger has correct action structure`() {
            val agent = reader.createAgentMetadata(TriggerChatAgent()) as CoreAgent
            val actionNames = agent.actions.map { it.name }
            println("Actions: $actionNames")
            assertTrue(actionNames.any { it.contains("handleMessage") })
        }

        @Test
        fun `multi-event agent has both actions`() {
            val agent = reader.createAgentMetadata(MultiEventAgent()) as CoreAgent
            val actionNames = agent.actions.map { it.name }
            println("Actions: $actionNames")
            assertTrue(actionNames.any { it.contains("processEventA") })
            assertTrue(actionNames.any { it.contains("processEventB") })
        }
    }

    @Nested
    inner class TriggerWithNullReturn {

        /**
         * This test demonstrates a BUG in the current @Trigger implementation:
         *
         * When an action with @Trigger and canRerun=true returns null/Unit,
         * the lastResult on the blackboard doesn't change. This means:
         * 1. The trigger precondition (lastResult:TriggerType) remains TRUE
         * 2. The action's canRerun=true allows it to run again
         * 3. The action runs again → infinite loop
         *
         * Expected behavior: The action should only run ONCE per trigger event.
         * Actual behavior: The action runs repeatedly until max iterations.
         */
        @Test
        fun `trigger action returning null should not loop infinitely`() {
            val agentInstance = NullReturningTriggerAgent()
            val agent = reader.createAgentMetadata(agentInstance) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                linkedMapOf(
                    "context" to RequestContext("ctx-001"),
                    "it" to IncomingRequest("req-001")
                )
            )

            println("Process status: ${process.status}")
            println("Invocation count: ${agentInstance.invocationCount}")
            println("History: ${process.history.map { it.actionName }}")

            // This assertion demonstrates the bug:
            // The action should run exactly once, but due to the bug it runs multiple times
            // (limited only by max iterations or other safeguards)
            assertEquals(
                1,
                agentInstance.invocationCount,
                "Action should only be invoked once per trigger, but was invoked ${agentInstance.invocationCount} times"
            )
        }
    }
}
