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
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Domain types for document processing
 */
data class Document(val content: String, val source: String)
data class ProcessedDocument(val content: String, val summary: String, val sentiment: String)

/**
 * A utility-based document processing agent that uses states to handle
 * different document types differently. This demonstrates combining
 * utility planning (opportunistic action selection) with state-based
 * branching.
 *
 * The key pattern for utility+state is:
 * 1. Entry action classifies and returns a @State type
 * 2. Each @State has a single @AchievesGoal action that produces the final output
 * 3. The final output is NOT a @State type (to prevent infinite loops)
 */
@Agent(
    description = "Process documents through classification and type-specific handling",
    planner = PlannerType.UTILITY
)
class DocumentProcessingAgent {

    @State
    sealed interface DocumentType

    @Action
    fun classifyDocument(document: Document): DocumentType {
        return when {
            document.content.contains("URGENT", ignoreCase = true) ->
                UrgentDocument(document)
            document.content.contains("report", ignoreCase = true) ->
                AnalyticalDocument(document)
            else ->
                StandardDocument(document)
        }
    }

    @State
    data class UrgentDocument(val document: Document) : DocumentType {
        @AchievesGoal(description = "Process urgent document with priority handling")
        @Action
        fun processUrgent(): ProcessedDocument {
            return ProcessedDocument(
                content = document.content,
                summary = "URGENT: ${document.source} - Priority escalation",
                sentiment = "CRITICAL"
            )
        }
    }

    @State
    data class AnalyticalDocument(val document: Document) : DocumentType {
        @AchievesGoal(description = "Process analytical document")
        @Action
        fun processAnalytical(): ProcessedDocument {
            val wordCount = document.content.split("\\s+".toRegex()).size
            return ProcessedDocument(
                content = document.content,
                summary = "Analysis from ${document.source} ($wordCount words)",
                sentiment = "INFORMATIVE"
            )
        }
    }

    @State
    data class StandardDocument(val document: Document) : DocumentType {
        @AchievesGoal(description = "Process standard document")
        @Action
        fun processStandard(): ProcessedDocument {
            return ProcessedDocument(
                content = document.content,
                summary = "Standard document from ${document.source}",
                sentiment = "NEUTRAL"
            )
        }
    }
}

/**
 * Ticket triage system that routes support tickets to different handling
 * workflows based on severity.
 */
@Agent(
    description = "Triage and process support tickets",
    planner = PlannerType.UTILITY
)
class TicketTriageAgent {

    data class Ticket(val id: String, val description: String, val customerId: String)
    data class ResolvedTicket(val id: String, val resolution: String, val handledBy: String)

    @State
    sealed interface TicketCategory

    @Action
    fun triageTicket(ticket: Ticket): TicketCategory {
        return when {
            ticket.description.contains("down", ignoreCase = true) ->
                CriticalTicket(ticket)
            ticket.description.contains("bug", ignoreCase = true) ->
                BugTicket(ticket)
            else ->
                GeneralTicket(ticket)
        }
    }

    @State
    data class CriticalTicket(val ticket: Ticket) : TicketCategory {
        @AchievesGoal(description = "Handle critical ticket with immediate escalation")
        @Action
        fun handleCritical(): ResolvedTicket {
            return ResolvedTicket(
                id = ticket.id,
                resolution = "Escalated to on-call engineer. Customer ${ticket.customerId} notified.",
                handledBy = "CRITICAL_RESPONSE_TEAM"
            )
        }
    }

    @State
    data class BugTicket(val ticket: Ticket) : TicketCategory {
        @AchievesGoal(description = "Handle bug report")
        @Action
        fun handleBug(): ResolvedTicket {
            return ResolvedTicket(
                id = ticket.id,
                resolution = "Bug logged in issue tracker. Will be addressed in next sprint.",
                handledBy = "ENGINEERING_TEAM"
            )
        }
    }

    @State
    data class GeneralTicket(val ticket: Ticket) : TicketCategory {
        @AchievesGoal(description = "Handle general inquiry")
        @Action
        fun handleGeneral(): ResolvedTicket {
            return ResolvedTicket(
                id = ticket.id,
                resolution = "Response sent to customer with FAQ links.",
                handledBy = "SUPPORT_TEAM"
            )
        }
    }
}

class UtilityPlannerWithStateTest {

    private val reader = AgentMetadataReader()

    @Nested
    inner class DocumentProcessing {

        @Test
        fun `processes urgent document through priority handling`() {
            val agent = reader.createAgentMetadata(DocumentProcessingAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(plannerType = PlannerType.UTILITY)
                    .withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to Document("URGENT: Server is down!", "ops-alert"))
            )
            println("Status: ${process.status}")
            val history = process.history.map { it.actionName }
            println("History: $history")
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Should complete: $history")
            assertTrue(history.any { it.contains("classifyDocument") }, "Should classify: $history")
            assertTrue(history.any { it.contains("processUrgent") }, "Should process urgent: $history")
            val result = process.getValue("it", ProcessedDocument::class.java.name) as? ProcessedDocument
            assertNotNull(result)
            assertEquals("CRITICAL", result!!.sentiment)
            assertTrue(result.summary.contains("URGENT"))
        }

        @Test
        fun `processes analytical document`() {
            val agent = reader.createAgentMetadata(DocumentProcessingAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(plannerType = PlannerType.UTILITY)
                    .withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to Document("Quarterly report with analysis", "finance-dept"))
            )
            println("Status: ${process.status}")
            val history = process.history.map { it.actionName }
            println("History: $history")
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Should complete: $history")
            assertTrue(history.any { it.contains("classifyDocument") }, "Should classify: $history")
            assertTrue(history.any { it.contains("processAnalytical") }, "Should process analytical: $history")
            val result = process.getValue("it", ProcessedDocument::class.java.name) as? ProcessedDocument
            assertNotNull(result)
            assertEquals("INFORMATIVE", result!!.sentiment)
        }

        @Test
        fun `processes standard document directly`() {
            val agent = reader.createAgentMetadata(DocumentProcessingAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(plannerType = PlannerType.UTILITY)
                    .withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to Document("Hello, just checking in.", "customer-email"))
            )
            println("Status: ${process.status}")
            val history = process.history.map { it.actionName }
            println("History: $history")
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Should complete: $history")
            assertTrue(history.any { it.contains("classifyDocument") }, "Should classify: $history")
            assertTrue(history.any { it.contains("processStandard") }, "Should process standard: $history")
            val result = process.getValue("it", ProcessedDocument::class.java.name) as? ProcessedDocument
            assertNotNull(result)
            assertEquals("NEUTRAL", result!!.sentiment)
        }
    }

    @Nested
    inner class TicketTriage {

        @Test
        fun `routes critical ticket to emergency handling`() {
            val agent = reader.createAgentMetadata(TicketTriageAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(plannerType = PlannerType.UTILITY)
                    .withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to TicketTriageAgent.Ticket("T-001", "Production database is down!", "CUST-123"))
            )
            println("Status: ${process.status}")
            val history = process.history.map { it.actionName }
            println("History: $history")
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Should complete: $history")
            assertTrue(history.any { it.contains("triageTicket") }, "Should triage: $history")
            assertTrue(history.any { it.contains("handleCritical") }, "Should handle critical: $history")
            val result = process.getValue("it", TicketTriageAgent.ResolvedTicket::class.java.name) as? TicketTriageAgent.ResolvedTicket
            assertNotNull(result)
            assertEquals("CRITICAL_RESPONSE_TEAM", result!!.handledBy)
        }

        @Test
        fun `routes bug ticket to engineering`() {
            val agent = reader.createAgentMetadata(TicketTriageAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(plannerType = PlannerType.UTILITY)
                    .withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to TicketTriageAgent.Ticket("T-002", "Found a bug in the checkout flow", "CUST-456"))
            )
            println("Status: ${process.status}")
            val history = process.history.map { it.actionName }
            println("History: $history")
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Should complete: $history")
            assertTrue(history.any { it.contains("triageTicket") }, "Should triage: $history")
            assertTrue(history.any { it.contains("handleBug") }, "Should handle bug: $history")
            val result = process.getValue("it", TicketTriageAgent.ResolvedTicket::class.java.name) as? TicketTriageAgent.ResolvedTicket
            assertNotNull(result)
            assertEquals("ENGINEERING_TEAM", result!!.handledBy)
        }

        @Test
        fun `routes general inquiry to support`() {
            val agent = reader.createAgentMetadata(TicketTriageAgent()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(plannerType = PlannerType.UTILITY)
                    .withVerbosity(Verbosity.DEFAULT.showPlanning()),
                mapOf("it" to TicketTriageAgent.Ticket("T-003", "How do I reset my password?", "CUST-789"))
            )
            println("Status: ${process.status}")
            val history = process.history.map { it.actionName }
            println("History: $history")
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Should complete: $history")
            assertTrue(history.any { it.contains("triageTicket") }, "Should triage: $history")
            assertTrue(history.any { it.contains("handleGeneral") }, "Should handle general: $history")
            val result = process.getValue("it", TicketTriageAgent.ResolvedTicket::class.java.name) as? TicketTriageAgent.ResolvedTicket
            assertNotNull(result)
            assertEquals("SUPPORT_TEAM", result!!.handledBy)
        }
    }

    @Nested
    inner class MetadataUnrolling {

        @Test
        fun `document agent has correct actions from states`() {
            val agent = reader.createAgentMetadata(DocumentProcessingAgent()) as CoreAgent
            val actionNames = agent.actions.map { it.name }
            println("Actions: $actionNames")
            assertTrue(actionNames.any { it.contains("classifyDocument") })
            assertTrue(actionNames.any { it.contains("processUrgent") })
            assertTrue(actionNames.any { it.contains("processAnalytical") })
            assertTrue(actionNames.any { it.contains("processStandard") })
        }

        @Test
        fun `document agent has goals for each document type`() {
            val agent = reader.createAgentMetadata(DocumentProcessingAgent()) as CoreAgent
            val goalNames = agent.goals.map { it.name }
            println("Goals: $goalNames")
            // 3 document type goals + 1 Nirvana (synthetic goal for utility planner)
            assertEquals(4, agent.goals.size, "Should have 4 goals (3 document types + Nirvana): $goalNames")
            assertTrue(goalNames.any { it.contains("processUrgent") })
            assertTrue(goalNames.any { it.contains("processAnalytical") })
            assertTrue(goalNames.any { it.contains("processStandard") })
            assertTrue(goalNames.any { it == "Nirvana" })
        }

        @Test
        fun `ticket agent has correct triage structure`() {
            val agent = reader.createAgentMetadata(TicketTriageAgent()) as CoreAgent
            val actionNames = agent.actions.map { it.name }
            println("Actions: $actionNames")
            assertTrue(actionNames.any { it.contains("triageTicket") })
            assertTrue(actionNames.any { it.contains("handleCritical") })
            assertTrue(actionNames.any { it.contains("handleBug") })
            assertTrue(actionNames.any { it.contains("handleGeneral") })
        }
    }
}
