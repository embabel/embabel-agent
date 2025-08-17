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
package com.embabel.agent.support

import com.embabel.agent.core.Agent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.support.DefaultAgentProcessIdGenerator
import com.embabel.agent.spi.support.DefaultProcessIdGeneratorProperties
import com.embabel.agent.config.AgentPlatformProperties
import com.embabel.agent.testing.integration.forProcessIdGenerationTesting
import com.embabel.common.core.MobyNameGenerator
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class DefaultProcessIdGeneratorTest {

    @Test
    fun `without agent name or version`() {
        val generator = DefaultAgentProcessIdGenerator(
            properties = forProcessIdGenerationTesting(
                includeVersion = false,
                includeAgentName = false,
            ),
            nameGenerator = MobyNameGenerator,
        )
        val mockAgent = mockk<Agent>()
        val mockProcessOptions = mockk<ProcessOptions>()
        val processId = generator.createProcessId(mockAgent, mockProcessOptions)
        assertTrue(processId.length <= 100, "Process ID should be short: '$processId'")
        assertFalse(processId.contains(" "), "Process ID should not contain spaces: '$processId'")
        assertFalse(processId.startsWith("-"), "Process ID should not start with a dash: '$processId'")
    }

    @Test
    @Disabled("not yet implemented")
    fun `with agent name but without version`() {
    }

    @Test
    @Disabled("not yet implemented")
    fun `with agent name and version`() {
    }
}
