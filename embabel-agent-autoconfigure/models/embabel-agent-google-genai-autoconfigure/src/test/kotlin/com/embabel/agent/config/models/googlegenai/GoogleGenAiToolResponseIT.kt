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
package com.embabel.agent.config.models.googlegenai

import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.autoconfigure.models.googlegenai.AgentGoogleGenAiAutoConfiguration
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.ToolCall
import com.embabel.chat.ToolResultMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * The model is called again after a tool result, as a tool loop does, against the real Gemini API.
 * A tool result of plain text that starts with a bracket used to fail that call before it was sent,
 * on every retry (#2196); the JSON results must still go through as before.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+", disabledReason = "Integration test requires GEMINI_API_KEY")
class GoogleGenAiToolResponseIT {

    private val writeFiles = Tool.create("writeFiles", "Writes the report files") { Tool.Result.text("unused") }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "[Done] 3 files written to output/",
            "3 files written to output/",
            """{"written": 3, "directory": "output/"}""",
            """["output/a.md", "output/b.md", "output/c.md"]""",
        ]
    )
    fun `the model answers after a tool result`(toolResult: String) {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentGoogleGenAiAutoConfiguration::class.java))
            .withPropertyValues(
                "embabel.agent.platform.models.googlegenai.api-key=\${GOOGLE_API_KEY:\${GEMINI_API_KEY:dummy-key-for-context-loading}}",
                "embabel.agent.platform.models.googlegenai.max-attempts=1",
            )
            .run { context ->
                val service = context.getBeansOfType(SpringAiLlmService::class.java).values
                    .first { it.name == GoogleGenAiModels.GEMINI_2_5_FLASH }
                val response = service.createMessageSender(LlmOptions()).call(
                    messages = listOf(
                        UserMessage("Write the report files, then tell me in one sentence what was written."),
                        AssistantMessageWithToolCalls(toolCalls = listOf(ToolCall("call-1", "writeFiles", "{}"))),
                        ToolResultMessage("call-1", "writeFiles", toolResult),
                    ),
                    tools = listOf(writeFiles),
                )

                assertTrue(response.textContent.isNotBlank(), "the model answered after the tool result")
            }
    }
}
