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
package com.embabel.agent.config.models.ollama

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.embabel.agent.core.Usage
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.agent.spi.loop.streaming.LlmInferenceStreamEvent
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.agent.spi.loop.streaming.StreamingToolLoop
import com.embabel.chat.AssistantMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.SystemMessage
import com.embabel.chat.ToolCall
import com.embabel.chat.ToolResultMessage
import com.embabel.chat.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.model.chat.StreamingChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler
import dev.langchain4j.model.ollama.OllamaStreamingChatModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment
import reactor.core.publisher.Flux
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves that the provider-neutral streaming loop can be driven by LangChain4j.
 *
 * Tool execution remains in [StreamingToolLoop]. The adapter only translates
 * messages and tool definitions and assembles LangChain4j's terminal response.
 */
@EnabledIfEnvironmentVariable(
    named = "OLLAMA_BASE_URL",
    matches = ".+",
    disabledReason = "Integration test requires OLLAMA_BASE_URL",
)
class LangChain4jStreamingToolLoopIT {

    private val environment: Environment = StandardEnvironment()

    @Test
    fun `executes a tool and continues streaming through LangChain4j`() {
        val model = OllamaStreamingChatModel.builder()
            .baseUrl(environment.getRequiredProperty("ollama.base-url"))
            .modelName(environment.getProperty("ollama.model", "qwen2.5:3b"))
            .temperature(0.0)
            .numCtx(4096)
            .numPredict(128)
            .timeout(Duration.ofMinutes(2))
            .build()
        val calls = AtomicInteger()
        val status = Tool.of("current_status", "Return the current system status") {
            calls.incrementAndGet()
            Tool.Result.text("GREEN")
        }

        val chunks = StreamingToolLoop.create(
            messageStreamer = LangChain4jMessageStreamer(model),
            objectMapper = jacksonObjectMapper(),
        ).execute(
            initialMessages = listOf(
                SystemMessage("You must call current_status before answering."),
                UserMessage("Call current_status now, then report its result in one short sentence."),
            ),
            initialTools = listOf(status),
        ).collectList().block(Duration.ofMinutes(2))

        assertThat(calls.get()).isEqualTo(1)
        assertThat(chunks).isNotNull.isNotEmpty
        assertThat(chunks!!.joinToString("")).containsIgnoringCase("green")
    }

    @Test
    fun `unfolds a child tool for the next LangChain4j streaming turn`() {
        val model = OllamaStreamingChatModel.builder()
            .baseUrl(environment.getRequiredProperty("ollama.base-url"))
            .modelName(environment.getProperty("ollama.model", "qwen2.5:3b"))
            .temperature(0.0)
            .numCtx(4096)
            .numPredict(192)
            .timeout(Duration.ofMinutes(2))
            .build()
        val childCalls = AtomicInteger()
        val child = Tool.of("read_secret_code", "Return the secret code") {
            childCalls.incrementAndGet()
            Tool.Result.text("ORANGE-42")
        }
        val facade = UnfoldingTool.of(
            name = "secret_tools",
            description = "Reveal the tool needed to read the secret code",
            innerTools = listOf(child),
            childToolUsageNotes = "Call read_secret_code next, then report its result.",
        )
        val toolsByTurn = mutableListOf<List<String>>()

        val chunks = StreamingToolLoop.create(
            messageStreamer = LangChain4jMessageStreamer(model) { tools ->
                toolsByTurn += tools.map { it.definition.name }
            },
            objectMapper = jacksonObjectMapper(),
            injectionStrategy = ToolInjectionStrategy.DEFAULT,
        ).execute(
            initialMessages = listOf(
                SystemMessage(
                    "First call secret_tools. After it reveals a child tool, call that child tool. " +
                        "Only then report the secret code."
                ),
                UserMessage("Use the available tools to obtain the secret code."),
            ),
            initialTools = listOf(facade),
        ).collectList().block(Duration.ofMinutes(2))

        assertThat(toolsByTurn).hasSizeGreaterThanOrEqualTo(3)
        assertThat(toolsByTurn[0]).containsExactly("secret_tools")
        assertThat(toolsByTurn[1]).containsExactly("read_secret_code")
        assertThat(childCalls.get()).isEqualTo(1)
        assertThat(chunks).isNotNull.isNotEmpty
        assertThat(chunks!!.joinToString("")).containsIgnoringCase("orange-42")
    }
}

private class LangChain4jMessageStreamer(
    private val model: StreamingChatModel,
    private val onTools: (List<Tool>) -> Unit = {},
) : LlmMessageStreamer {

    override fun stream(
        messages: List<Message>,
        tools: List<Tool>,
        toolCallInspectors: List<ToolCallInspector>,
    ): Flux<String> = streamInference(messages, tools)
        .ofType(LlmInferenceStreamEvent.Content::class.java)
        .map { it.text }

    override fun streamInference(
        messages: List<Message>,
        tools: List<Tool>,
    ): Flux<LlmInferenceStreamEvent> = Flux.create { sink ->
        onTools(tools)
        val request = ChatRequest.builder()
            .messages(messages.map(::toLangChain4jMessage))
            .toolSpecifications(tools.map(::toLangChain4jTool))
            .build()

        model.chat(request, object : StreamingChatResponseHandler {
            override fun onPartialResponse(partialResponse: String) {
                sink.next(LlmInferenceStreamEvent.Content(partialResponse))
            }

            override fun onCompleteResponse(completeResponse: ChatResponse) {
                sink.next(
                    LlmInferenceStreamEvent.Complete(
                        message = toEmbabelMessage(completeResponse.aiMessage()),
                        usage = completeResponse.tokenUsage()?.let {
                            Usage(it.inputTokenCount(), it.outputTokenCount(), it)
                        },
                    )
                )
                sink.complete()
            }

            override fun onError(error: Throwable) {
                sink.error(error)
            }
        })
    }

    private fun toLangChain4jMessage(message: Message): ChatMessage = when (message) {
        is SystemMessage -> dev.langchain4j.data.message.SystemMessage.from(message.content)
        is UserMessage -> dev.langchain4j.data.message.UserMessage.from(message.content)
        is AssistantMessageWithToolCalls -> AiMessage.from(
            message.content,
            message.toolCalls.map {
                ToolExecutionRequest.builder()
                    .id(it.id)
                    .name(it.name)
                    .arguments(it.arguments)
                    .build()
            },
        )

        is ToolResultMessage -> ToolExecutionResultMessage.from(
            message.toolCallId,
            message.toolName,
            message.content,
        )

        is AssistantMessage -> AiMessage.from(message.content)
        else -> error("Unsupported message type: ${message::class.qualifiedName}")
    }

    private fun toLangChain4jTool(tool: Tool): ToolSpecification =
        ToolSpecification.builder()
            .name(tool.definition.name)
            .description(tool.definition.description)
            .build()

    private fun toEmbabelMessage(message: AiMessage): Message =
        if (message.hasToolExecutionRequests()) {
            AssistantMessageWithToolCalls(
                content = message.text().orEmpty(),
                toolCalls = message.toolExecutionRequests().map {
                    ToolCall(
                        id = it.id(),
                        name = it.name(),
                        arguments = it.arguments(),
                    )
                },
            )
        } else {
            AssistantMessage(message.text().orEmpty())
        }
}
