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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.dsl.SnakeMeal
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.testing.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class AgentMetadataReaderImportTest {

    @Test
    fun `define flow`() {
        val reader = TestAgentMetadataReader.create()
        val agent = reader.createAgentMetadata(DefineFlowTest()) as Agent
        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val result = ap.runAgentFrom(
            agent, processOptions = ProcessOptions(),
            bindings = mapOf(
                "it" to UserInput("input"),
            ),
        )
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertTrue(result.lastResult() is PersonWithReverseTool)
    }

    @Test
    fun `local agent`() {
        val reader = TestAgentMetadataReader.create()
        val agent = reader.createAgentMetadata(LocalAgentTest()) as Agent
        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val result = ap.runAgentFrom(
            agent, processOptions = ProcessOptions(),
            bindings = mapOf(
                "it" to UserInput("input"),
            ),
        )
        assertEquals(AgentProcessStatusCode.COMPLETED, result.status)
        assertTrue(result.lastResult() is SnakeMeal)
    }

}
