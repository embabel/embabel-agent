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
package com.embabel.agent.api.annotation.support.state

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.State
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.embabel.chat.Conversation
import com.embabel.chat.UserMessage
import com.embabel.chat.support.InMemoryConversation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Tests to verify that conversation and other important bindings are preserved
 * across state transitions. Currently these tests FAIL because state transitions
 * clear the entire blackboard, including the conversation binding.
 *
 * Issue: When an @Action returns a @State type, the blackboard is cleared.
 * This causes conversation history and user identity to be lost.
 */
class StateConversationPreservationTest {

    private val reader = AgentMetadataReader()

    /**
     * A conversational agent that uses states to manage different conversation phases.
     * The conversation should be preserved across state transitions.
     */
    @Agent(description = "Conversational agent with states")
    class ConversationalStateAgent {

        @Action
        fun handleGreeting(message: UserMessage, conversation: Conversation): GreetingState {
            return GreetingState(conversation.id)
        }

        @State
        data class GreetingState(val conversationId: String) {
            @Action
            fun askQuestion(conversation: Conversation): QuestionState {
                // This should work - conversation should still be available
                return QuestionState(conversationId, conversation.messages.size)
            }
        }

        @State
        data class QuestionState(val conversationId: String, val messageCount: Int) {
            @AchievesGoal(description = "Conversation completed")
            @Action
            fun complete(conversation: Conversation): ConversationResult {
                return ConversationResult(
                    conversationId = conversationId,
                    totalMessages = conversation.messages.size,
                    wasPreserved = true
                )
            }
        }
    }

    data class ConversationResult(
        val conversationId: String,
        val totalMessages: Int,
        val wasPreserved: Boolean,
    )

    @Nested
    inner class ConversationPreservation {

        @Test
        fun `conversation binding is preserved across state transition`() {
            val agent = reader.createAgentMetadata(ConversationalStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            val conversation = InMemoryConversation(
                id = "test-conversation",
                messages = listOf(UserMessage("Hello"))
            )

            // Create process, bind conversation as protected, then run
            val process = ap.createAgentProcess(
                agent,
                ProcessOptions(),
                mapOf("it" to UserMessage("Hello"))
            )
            process.bindProtected("conversation", conversation)
            process.run()

            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")

            // The conversation binding should still exist after state transitions
            val conversationAfter = process["conversation"]
            assertNotNull(conversationAfter, "Conversation binding should be preserved after state transitions")
            assertSame(conversation, conversationAfter, "Should be the same conversation instance")
        }

        @Test
        fun `conversation is accessible within state actions`() {
            val agent = reader.createAgentMetadata(ConversationalStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            val conversation = InMemoryConversation(
                id = "test-conv-123",
                messages = listOf(
                    UserMessage("Hello"),
                    UserMessage("How are you?")
                )
            )

            // Create process, bind conversation as protected, then run
            val process = ap.createAgentProcess(
                agent,
                ProcessOptions(),
                mapOf("it" to UserMessage("Hello"))
            )
            process.bindProtected("conversation", conversation)
            process.run()

            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")

            // Check that the state actions were able to access the conversation
            val result = process.getValue("it", ConversationResult::class.java.name) as? ConversationResult
            assertNotNull(result, "Should have result")
            assertEquals("test-conv-123", result!!.conversationId, "Should have correct conversation ID")
            assertEquals(2, result.totalMessages, "Should see all messages in conversation")
        }
    }

    /**
     * Agent that demonstrates user identity should also be preserved across states.
     */
    @Agent(description = "Agent tracking user across states")
    class UserTrackingStateAgent {

        @Action
        fun startSession(userId: UserId): SessionState {
            return SessionState(userId.id)
        }

        @State
        data class SessionState(val userId: String) {
            @Action
            fun processRequest(userId: UserId): ProcessingState {
                // userId should still be accessible
                return ProcessingState(userId.id, userId.id == this.userId)
            }
        }

        @State
        data class ProcessingState(val userId: String, val userMatched: Boolean) {
            @AchievesGoal(description = "Request processed")
            @Action
            fun complete(userId: UserId): SessionResult {
                return SessionResult(
                    userId = userId.id,
                    userPreserved = userId.id == this.userId && userMatched
                )
            }
        }
    }

    data class UserId(val id: String)
    data class SessionResult(val userId: String, val userPreserved: Boolean)

    @Nested
    inner class UserIdentityPreservation {

        @Test
        fun `user identity binding is preserved across state transitions`() {
            val agent = reader.createAgentMetadata(UserTrackingStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            val userId = UserId("user-42")

            // Create process, bind userId as protected (simulating user identity), then run
            val process = ap.createAgentProcess(
                agent,
                ProcessOptions(),
                emptyMap()
            )
            process.bindProtected("userId", userId)
            process += userId  // Also add as default binding for initial action
            process.run()

            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")

            val result = process.getValue("it", SessionResult::class.java.name) as? SessionResult
            assertNotNull(result, "Should have result")
            assertEquals("user-42", result!!.userId, "Should have correct user ID")
            assertTrue(result.userPreserved, "User identity should be preserved across all state transitions")
        }
    }

    /**
     * Test that named bindings (not just default "it" binding) are preserved.
     */
    @Agent(description = "Agent with named bindings")
    class NamedBindingsStateAgent {

        @Action
        fun start(config: AppConfig): ConfiguredState {
            return ConfiguredState(config.setting)
        }

        @State
        data class ConfiguredState(val setting: String) {
            @AchievesGoal(description = "Config used")
            @Action
            fun useConfig(config: AppConfig): ConfigResult {
                return ConfigResult(
                    originalSetting = setting,
                    currentSetting = config.setting,
                    preserved = setting == config.setting
                )
            }
        }
    }

    data class AppConfig(val setting: String)
    data class ConfigResult(val originalSetting: String, val currentSetting: String, val preserved: Boolean)

    @Nested
    inner class NamedBindingPreservation {

        @Test
        fun `named bindings are preserved across state transitions`() {
            val agent = reader.createAgentMetadata(NamedBindingsStateAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            val config = AppConfig("production")

            // Create process, bind config as protected, then run
            val process = ap.createAgentProcess(
                agent,
                ProcessOptions(),
                emptyMap()
            )
            process.bindProtected("config", config)
            process += config  // Also add as default binding for initial action
            process.run()

            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")

            // Named binding should still exist
            val configAfter = process["config"]
            assertNotNull(configAfter, "Named 'config' binding should be preserved")

            val result = process.getValue("it", ConfigResult::class.java.name) as? ConfigResult
            assertNotNull(result, "Should have result")
            assertTrue(result!!.preserved, "Config should be accessible in state action")
        }
    }

    /**
     * Agent that enters a state and then exits back to outer context.
     * Demonstrates that protected bindings allow returning to "no state".
     */
    @Agent(description = "Agent that exits state back to outer context")
    class StateExitAgent {

        /**
         * Initial action that takes the original input and enters a state.
         */
        @Action
        fun enterState(input: OriginalInput): WorkingState {
            return WorkingState(input.data)
        }

        @State
        data class WorkingState(val data: String) {
            /**
             * Exit the state by returning a non-state type.
             * This should allow outer actions to run again.
             */
            @Action
            fun exitState(): StateExitSignal {
                return StateExitSignal(data.uppercase())
            }
        }

        /**
         * This action requires OriginalInput, which should still be available
         * after exiting the state (because it was protected).
         */
        @AchievesGoal(description = "Process completed after returning from state")
        @Action
        fun continueAfterState(input: OriginalInput, signal: StateExitSignal): FinalOutput {
            return FinalOutput(
                originalData = input.data,
                processedData = signal.result,
                returnedToOuter = true
            )
        }
    }

    data class OriginalInput(val data: String)
    data class StateExitSignal(val result: String)
    data class FinalOutput(val originalData: String, val processedData: String, val returnedToOuter: Boolean)

    @Nested
    inner class StateExitToOuterContext {

        @Test
        fun `can exit state and return to outer actions with protected bindings`() {
            val agent = reader.createAgentMetadata(StateExitAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()

            val input = OriginalInput("hello")

            // Bind input as protected so it survives state transition
            val process = ap.createAgentProcess(
                agent,
                ProcessOptions(),
                emptyMap()
            )
            process.bindProtected("input", input)
            process += input
            process.run()

            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")

            // Verify the flow: enterState -> exitState -> continueAfterState
            val history = process.history.map { it.actionName }
            assertTrue(history.any { it.contains("enterState") }, "Should have enterState: $history")
            assertTrue(history.any { it.contains("exitState") }, "Should have exitState: $history")
            assertTrue(history.any { it.contains("continueAfterState") }, "Should have continueAfterState: $history")

            val result = process.getValue("it", FinalOutput::class.java.name) as? FinalOutput
            assertNotNull(result, "Should have final output")
            assertEquals("hello", result!!.originalData, "Should have original data from protected binding")
            assertEquals("HELLO", result.processedData, "Should have processed data from state")
            assertTrue(result.returnedToOuter, "Should have returned to outer context")
        }
    }
}
