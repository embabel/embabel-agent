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
package com.embabel.agent.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.api.dsl.Frog
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun agentWithGoalNamed(agentName: String, goalName: String, meaning: String) =
    agent(agentName, description = "Turn a person into a frog") {
        transformation<UserInput, Frog>(name = "$agentName-action") { Frog(agentName) }
        goal(name = goalName, description = meaning, satisfiedBy = Frog::class)
    }

/**
 * [AgentPlatform] exposes goals, actions and conditions as flat views keyed by name, so two
 * agents contributing different elements under one name cannot both be honoured and one is
 * dropped. That is longstanding behaviour and this change does not alter it.
 *
 * What it alters is that the drop is no longer silent. The original report on this was that
 * both agents deployed, `agents()` returned both, nothing was logged, and the capability had
 * simply stopped existing.
 */
class AgentPlatformNameCollisionReportingTest {

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun attachAppender() {
        logger = LoggerFactory.getLogger("com.embabel.agent.core.support.NameCollisions") as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.ERROR
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun errorsMentioning(name: String): List<String> =
        appender.list
            .filter { it.level == Level.ERROR }
            .map { it.formattedMessage }
            .filter { it.contains(name) }

    private fun goalName(suffix: String) = "reporting-test-$suffix-${System.nanoTime()}"

    @Test
    fun `a goal lost to another agent's goal of the same name is reported`() {
        val shared = goalName("lost")
        val platform = IntegrationTestUtils.dummyAgentPlatform()
        platform.deploy(agentWithGoalNamed("AardvarkWizard", shared, "what Aardvark means"))
        platform.deploy(agentWithGoalNamed("ZebraWizard", shared, "what Zebra means"))

        assertEquals(2, platform.agents().size, "Both agents still deploy: deployment does not validate")
        assertEquals(
            1,
            platform.goals.count { it.name == shared },
            "One goal is still dropped: the view is keyed by name",
        )
        assertEquals(
            1,
            errorsMentioning(shared).size,
            "The drop must be reported: ${appender.list.map { it.formattedMessage }}",
        )
    }

    @Test
    fun `two agents declaring the very same goal are not reported`() {
        val shared = goalName("identical")
        val platform = IntegrationTestUtils.dummyAgentPlatform()
        platform.deploy(agentWithGoalNamed("AardvarkWizard", shared, "one meaning, agreed by both"))
        platform.deploy(agentWithGoalNamed("ZebraWizard", shared, "one meaning, agreed by both"))

        assertEquals(1, platform.goals.count { it.name == shared }, "Collapsing them loses nothing")
        assertTrue(
            errorsMentioning(shared).isEmpty(),
            "Nothing was lost, so there is nothing to report: ${errorsMentioning(shared)}",
        )
    }

    @Test
    fun `goals with distinct names are all kept and nothing is reported`() {
        val aardvark = goalName("aardvark")
        val zebra = goalName("zebra")
        val platform = IntegrationTestUtils.dummyAgentPlatform()
        platform.deploy(agentWithGoalNamed("AardvarkWizard", aardvark, "what Aardvark means"))
        platform.deploy(agentWithGoalNamed("ZebraWizard", zebra, "what Zebra means"))

        assertEquals(
            setOf(aardvark, zebra),
            platform.goals.map { it.name }.filter { it.startsWith("reporting-test-") }.toSet(),
        )
        assertTrue(errorsMentioning(aardvark).isEmpty() && errorsMentioning(zebra).isEmpty())
    }
}
