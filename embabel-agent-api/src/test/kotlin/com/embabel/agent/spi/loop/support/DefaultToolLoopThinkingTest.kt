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
package com.embabel.agent.spi.loop.support

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.ToolCall
import com.embabel.chat.UserMessage
import com.embabel.common.core.thinking.ThinkingException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

class DefaultToolLoopThinkingTest {

    @Test
    fun `accumulates provider thinking across tool loop iterations`() {
        var calls = 0
        val sender = LlmMessageSender { _, _ ->
            calls++
            if (calls == 1) {
                LlmMessageResponse(
                    message = AssistantMessageWithToolCalls(
                        content = "",
                        toolCalls = listOf(ToolCall("call-1", "lookup", "{}")),
                    ),
                    textContent = "",
                    thinkingContent = listOf("choose lookup"),
                )
            } else {
                LlmMessageResponse(
                    message = AssistantMessage("done"),
                    textContent = "done",
                    thinkingContent = listOf("summarize result"),
                )
            }
        }
        val tool = Tool.of("lookup", "look up a value") { Tool.Result.text("found") }

        val result = DefaultToolLoop(sender, jacksonObjectMapper()).execute(
            initialMessages = listOf(UserMessage("question")),
            initialTools = listOf(tool),
            outputParser = { it },
        )

        assertThat(result.result).isEqualTo("done")
        assertThat(result.thinkingContent).containsExactly("choose lookup", "summarize result")
    }

    @Test
    fun `preserves provider thinking when final output parsing fails`() {
        val sender = LlmMessageSender { _, _ ->
            LlmMessageResponse(
                message = AssistantMessage("invalid"),
                textContent = "invalid",
                thinkingContent = listOf("provider reasoning", "provider reasoning"),
            )
        }

        assertThatThrownBy {
            DefaultToolLoop(sender, jacksonObjectMapper()).execute(
                initialMessages = listOf(UserMessage("question")),
                initialTools = emptyList(),
                outputParser = {
                    throw ThinkingException("conversion failed", emptyList())
                },
            )
        }.isInstanceOfSatisfying(ThinkingException::class.java) { exception ->
            assertThat(exception.thinkingBlocks.map { it.content })
                .containsExactly("provider reasoning", "provider reasoning")
        }
    }
}
