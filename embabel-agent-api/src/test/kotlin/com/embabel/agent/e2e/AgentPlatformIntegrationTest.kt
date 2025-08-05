/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.api.common.NoSuchAgentException
import com.embabel.agent.api.common.TypedOps
import com.embabel.agent.api.common.asFunction
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.GoalChoiceApprover
import com.embabel.agent.api.dsl.EvilWizardAgent
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.spi.Ranking
import com.embabel.agent.spi.Rankings
import com.embabel.agent.testing.integration.FakeRanker
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import com.embabel.example.simple.horoscope.TestHoroscopeService
import com.embabel.example.simple.horoscope.java.TestStarNewsFinder
import com.embabel.example.simple.horoscope.kotlin.Writeup
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertNotNull

@TestConfiguration
class FakeConfig {

    @Bean
    @Primary
    fun fakeHoroscopeService() = TestHoroscopeService {
        """"
            |On Monday, try to avoid being eaten by wolves            .
        """.trimMargin()
    }

    @Bean
    @Primary
    fun fakeRanker() = object : FakeRanker {

        override fun <T> rank(
            description: String,
            userInput: String,
            rankables: Collection<T>,
        ): Rankings<T> where T : Named, T : Described {
            when (description) {
                "agent" -> {
                    val a = rankables.firstOrNull { it.name.contains("Star") } ?: fail { "No agent with Star found" }
                    return Rankings(
                        rankings = listOf(Ranking(a, .9))
                    )
                }

                "goal" -> {
                    val g =
                        rankables.firstOrNull { it.description.contains("horoscope") } ?: fail("No goal with horoscope")
                    return Rankings(
                        rankings = listOf(Ranking(g, .9))
                    )
                }

                else -> throw IllegalArgumentException("Unknown description $description")
            }
        }
    }

}

/**
 * Integration tests
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(
    value = [
        FakeConfig::class,
    ]
)
class AgentPlatformIntegrationTest(
    @param:Autowired
    private val autonomy: Autonomy,
    @param:Autowired
    private val agentMetadataReader: AgentMetadataReader,
    @param:Autowired
    private val horoscopeService: TestHoroscopeService,
) {

    private val agentPlatform: AgentPlatform = autonomy.agentPlatform

    private val typedOps: TypedOps = AgentPlatformTypedOps(autonomy.agentPlatform)

    @BeforeEach
    fun setup() {
        agentMetadataReader.createAgentScopes(
            com.embabel.example.simple.horoscope.kotlin.TestStarNewsFinder(
                horoscopeService,
                wordCount = 100,
                storyCount = 5,
            ),
            TestStarNewsFinder(horoscopeService, 5),
        ).forEach { agentPlatform.deploy(it) }
    }

    @Nested
    inner class Repository {

        @Test
        fun `process not started`() {
            val ap = agentPlatform.createAgentProcess(evenMoreEvilWizard(), ProcessOptions(), emptyMap())
            assertEquals(AgentProcessStatusCode.NOT_STARTED, ap.status)
        }

        @Test
        fun `process not started via repository`() {
            val ap = agentPlatform.createAgentProcess(evenMoreEvilWizard(), ProcessOptions(), emptyMap())
            val ap2 = agentPlatform.getAgentProcess(ap.id)
            assertNotNull(ap2, "Process should be saved to repository")
            assertEquals(AgentProcessStatusCode.NOT_STARTED, ap2.status)
        }
    }


    @Nested
    inner class SmokeTest {

        @Test
        fun `AgentPlatform starts up`() {
            // Nothing to test
        }
    }

    @Nested
    inner class Transforms {

        @Test
        fun `run Java star finder as transform by name`() {
            val writeup = typedOps.asFunction<UserInput, HasContent>(
                outputClass = HasContent::class.java,
                agentName = "JavaTestStarNewsFinder",
            ).apply(
                UserInput("Lynda is a Scorpio, find some news for her"),
                ProcessOptions(test = true),
            )
            assertNotNull(writeup)
            assertNotNull(writeup.content)
        }

        @Test
        fun `run Kotlin star finder as transform by name`() {
            val writeup = typedOps.asFunction<UserInput, HasContent>(
                outputClass = HasContent::class.java,
                agentName = "TestStarNewsFinder",
            ).apply(
                UserInput("Lynda is a Scorpio, find some news for her"),
                ProcessOptions(test = true),
            )
            assertNotNull(writeup)
            assertNotNull(writeup.content)
        }

        @Test
        fun `reject unknown agent in transform by name`() {
            assertThrows<NoSuchAgentException> {
                typedOps.asFunction<UserInput, Writeup>(
                    outputClass = Writeup::class.java,
                    agentName = "stuff and nonsense",
                )
            }
        }

        @Test
        fun `run dsl agent as transform`() {
            autonomy.agentPlatform.deploy(EvilWizardAgent)
            val frog = typedOps.asFunction<UserInput, Frog>(
            ).apply(
                UserInput("Hamish a poor boy"),
                ProcessOptions(test = true),
            )
            assertNotNull(frog)
        }
    }

    @Nested
    inner class ClosedRunAgent {

        @Test
        fun `choose and run star finder agent`() {
            val dynamicExecutionResult = autonomy.chooseAndRunAgent(
                "Lynda is a Scorpio, find some news for her",
                ProcessOptions(test = true),
            )
            assertNotNull(dynamicExecutionResult.output)
            assertTrue(
                dynamicExecutionResult.output is HasContent,
                "Expected HasContent, got ${dynamicExecutionResult.output.javaClass.name}"
            )
        }
    }

    @Nested
    inner class OpenAccomplishGoal {

        @Test
        fun `run star finder agent`() {
            val dynamicExecutionResult = autonomy.chooseAndAccomplishGoal(
                processOptions = ProcessOptions(test = true),
                goalChoiceApprover = GoalChoiceApprover.APPROVE_ALL,
                agentScope = agentPlatform,
                bindings = mapOf(
                    "userInput" to UserInput("Lynda is a Scorpio, find some news for her"),
                ),
            )
            assertNotNull(dynamicExecutionResult.output)
            assertTrue(
                dynamicExecutionResult.output is HasContent,
                "Expected HasContent, got ${dynamicExecutionResult.output.javaClass.name}"
            )
        }

    }
}
