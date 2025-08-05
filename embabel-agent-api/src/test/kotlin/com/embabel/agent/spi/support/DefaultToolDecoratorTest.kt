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
package com.embabel.agent.spi.support

import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.testing.integration.IntegrationTestUtils.dummyAgentProcessRunning
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Test
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.annotation.Tool
import kotlin.test.assertTrue

object RuntimeExceptionTool {
    @Tool
    fun toolThatThrowsRuntimeException(input: String): String {
        throw RuntimeException("This tool always fails")
    }
}


class DefaultToolDecoratorTest {


    @Test
    fun `test handle runtime exception from tool`() {
        val toolDecorator = DefaultToolDecorator()
        val badToolCallback = ToolCallbacks.from(RuntimeExceptionTool).single()
        val decorated = toolDecorator.decorate(
            tool = badToolCallback,
            agentProcess = dummyAgentProcessRunning(evenMoreEvilWizard()),
            action = null, llmOptions = LlmOptions(),
        )
        val result = decorated.call(
            """
            { "input": "anything at all" }
        """.trimIndent()
        )
        assertTrue(
            result.contains("This tool always fails"),
            "Expected result to contain the exception message: Got '$result'"
        )
    }

}
