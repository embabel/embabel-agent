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
package com.embabel.agent.event

import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.event.*
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import com.embabel.common.util.loggerFor
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import kotlin.test.assertTrue

/**
 * Check that we can safely serialize AgentProcessEvent.
 * We don't need to deserialize it.
 */
class AgenticEventSerializationTest {

    @Test
    fun `test process events can be serialized`() {
        val om = jacksonObjectMapper().registerModule(JavaTimeModule())
        val fails = mutableListOf<String>()
        var agentProcessPlanFormulatedEventString: String? = null
        var agentProcessCompletedEventString: String? = null

        val serializingListener = object : AgenticEventListener {
            var count = 0
            override fun onProcessEvent(event: AgentProcessEvent) {
                val s = om.writeValueAsString(event)
                if (!s.contains("\"type\"")) {
                    fails.add(s)
                }
                count++
                loggerFor<AgenticEventSerializationTest>().info("Serialized event: $s")
                when (event) {
                    is AgentProcessPlanFormulatedEvent -> {
                        agentProcessPlanFormulatedEventString = s
                    }

                    is AgentProcessCompletedEvent -> {
                        agentProcessCompletedEventString = s
                    }

                    else -> {
                        // Other events are not required to have a plan or goal
                    }
                }
                assertTrue(s.contains("\"processId\""), "Process id is required")
            }
        }

        val saver = EventSavingAgenticEventListener()
        val ap = dummyAgentPlatform(listener = AgenticEventListener.of(saver, serializingListener))
        // If it doesn't die we're happy
        ap.runAgentFrom(
            agent = evenMoreEvilWizard(),
            bindings = mapOf("it" to UserInput("anything at all"))
        )
        assertTrue(serializingListener.count > 0, "Events were serialized")
        assertEquals(0, fails.size, "Untyped events:\n${fails.joinToString(", ")}")
        assertTrue(
            saver.processEvents.filterIsInstance<ObjectBindingEvent>().isNotEmpty(),
            "Object binding events were emitted"
        )

        assertNotNull(agentProcessPlanFormulatedEventString)
        assertTrue(
            agentProcessPlanFormulatedEventString.contains("\"plan\""),
            "Plan is required in $agentProcessCompletedEventString"
        )
        assertTrue(
            agentProcessPlanFormulatedEventString.contains("\"goal\""),
            "Goal is required in $agentProcessCompletedEventString"
        )
        assertNotNull(agentProcessCompletedEventString)
        assertTrue(
            agentProcessCompletedEventString.contains("\"history\""),
            "History is required in $agentProcessCompletedEventString"
        )
//        assertTrue(
//            agentProcessCompletedEventString.contains("\"goal\""),
//            "Goal is required in $agentProcessCompletedEventString"
//        )
    }

    @Test
    fun `test platform events can be serialized`() {
        val om = jacksonObjectMapper().registerModule(JavaTimeModule())
        val fails = mutableListOf<String>()

        val serializingListener = object : AgenticEventListener {
            var count = 0
            override fun onPlatformEvent(event: AgentPlatformEvent) {
                val s = om.writeValueAsString(event)
                if (!s.contains("\"type\"")) {
                    fails.add(s)
                }
                count++
                loggerFor<AgenticEventSerializationTest>().info("Serialized event: $s")
            }
        }
        val ap = dummyAgentPlatform(listener = serializingListener)
        // If it doesn't die we're happy
        ap.deploy(evenMoreEvilWizard())
        assertTrue(serializingListener.count > 0, "Events were serialized")
        assertEquals(0, fails.size, "Untyped events:\n${fails.joinToString(", ")}")
    }

}
