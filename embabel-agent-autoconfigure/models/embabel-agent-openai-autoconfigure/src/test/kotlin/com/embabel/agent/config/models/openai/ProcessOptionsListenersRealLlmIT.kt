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
package com.embabel.agent.config.models.openai

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.event.AgentProcessCompletedEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration
import com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Real-LLM reproduction of https://github.com/embabel/embabel-agent/issues/2126.
 *
 * Listeners passed via [ProcessOptions.listeners] are documented to receive events from
 * their process. They must see the same process events as the platform listener,
 * including lifecycle events, and including those of sub-processes that inherit them.
 *
 * Uses gpt-4.1-mini. Requires the `OPENAI_API_KEY` environment variable.
 */
@SpringBootTest(
    properties = [
        "embabel.models.cheapest=gpt-4.1-mini",
        "embabel.models.best=gpt-4.1-mini",
        "embabel.models.default-llm=gpt-4.1-mini",
        "embabel.agent.platform.llm-operations.prompts.defaultTimeout=240s",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("thinking")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent", "com.embabel.example"])
@ComponentScan(
    basePackages = ["com.embabel.agent", "com.embabel.example"],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = [".*GlobalExceptionHandler.*"]
        )
    ]
)
@Import(AgentOpenAiAutoConfiguration::class, ProcessOptionsListenersRealLlmIT.PlatformListenerConfig::class)
@ImportAutoConfiguration(AgentPlatformAutoConfiguration::class)
@EnabledIfEnvironmentVariable(
    named = "OPENAI_API_KEY",
    matches = ".+",
    disabledReason = "Integration test requires OPENAI_API_KEY",
)
class ProcessOptionsListenersRealLlmIT {

    private val logger = LoggerFactory.getLogger(ProcessOptionsListenersRealLlmIT::class.java)

    @Autowired
    private lateinit var agentPlatform: AgentPlatform

    @Autowired
    private lateinit var platformListener: RecordingListener

    class RecordingListener : AgenticEventListener {
        val events = CopyOnWriteArrayList<AgentProcessEvent>()

        override fun onProcessEvent(event: AgentProcessEvent) {
            events += event
        }

        /** Event counts by type, for one process. */
        fun countsFor(processId: String): Map<String?, Int> =
            events.filter { it.processId == processId }.groupingBy { it::class.simpleName }.eachCount()
    }

    /** Registered as a bean, so the platform picks it up as a platform listener. */
    @Configuration
    class PlatformListenerConfig {
        @Bean
        fun platformRecordingListener() = RecordingListener()
    }

    data class Capital(val city: String)

    @Agent(
        name = "CapitalAgent",
        description = "Asks a real LLM for the capital of a country",
        scan = false,
    )
    class CapitalAgent {

        @Action
        @AchievesGoal(description = "Capital found")
        fun findCapital(input: UserInput, context: OperationContext): Capital =
            context.ai()
                .withDefaultLlm()
                .createObject(
                    """
                    What is the capital of ${input.content}?
                    Return JSON with a single field "city".
                    """.trimIndent(),
                    Capital::class.java,
                )
    }

    @Agent(
        name = "CapitalParentAgent",
        description = "Delegates the capital lookup to a sub-process",
        scan = false,
    )
    class CapitalParentAgent {

        @Action
        @AchievesGoal(description = "Capital found by a sub-process")
        fun delegate(input: UserInput, context: ActionContext): Capital {
            val subAgent = AgentMetadataReader()
                .createAgentMetadata(CapitalAgent()) as com.embabel.agent.core.Agent
            return context.asSubProcess(Capital::class.java, subAgent)
        }
    }

    @BeforeEach
    fun reset() {
        platformListener.events.clear()
    }

    @Test
    fun `process options listener receives the same events as the platform listener`() {
        val processListener = RecordingListener()
        val agent = AgentMetadataReader()
            .createAgentMetadata(CapitalAgent()) as com.embabel.agent.core.Agent

        val process = agentPlatform.runAgentFrom(
            agent,
            ProcessOptions(listeners = listOf(processListener)),
            mapOf("it" to UserInput("France")),
        ) as AgentProcess
        assertEquals(AgentProcessStatusCode.COMPLETED, process.status)

        val platformCounts = platformListener.countsFor(process.id)
        val processCounts = processListener.countsFor(process.id)
        logger.info("Platform listener events: {}", platformCounts)
        logger.info("ProcessOptions listener events: {}", processCounts)

        assertTrue(
            processListener.events.any { it is LlmRequestEvent<*> },
            "The real LLM call should be seen. Received: $processCounts",
        )
        assertEquals(
            platformCounts,
            processCounts,
            "ProcessOptions listener must get the same events, as often, as the platform listener",
        )
    }

    @Test
    fun `inherited process options listener receives the same sub-process events as the platform listener`() {
        val processListener = RecordingListener()
        val parentAgent = AgentMetadataReader()
            .createAgentMetadata(CapitalParentAgent()) as com.embabel.agent.core.Agent

        val parent = agentPlatform.runAgentFrom(
            parentAgent,
            ProcessOptions(listeners = listOf(processListener)),
            mapOf("it" to UserInput("France")),
        ) as AgentProcess
        assertEquals(AgentProcessStatusCode.COMPLETED, parent.status)
        val child = agentPlatform.platformServices.agentProcessRepository.findByParentId(parent.id).single()
        assertEquals(AgentProcessStatusCode.COMPLETED, child.status)

        val childCounts = processListener.countsFor(child.id)
        logger.info("ProcessOptions listener events for child {}: {}", child.id, childCounts)

        assertTrue(
            processListener.events.any { it is AgentProcessCompletedEvent && it.processId == child.id },
            "Inherited listener should receive the child's AgentProcessCompletedEvent. Received: $childCounts",
        )
        assertEquals(
            platformListener.countsFor(child.id),
            childCounts,
            "Inherited listener must get the same child events, as often, as the platform listener",
        )
    }
}
