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
@file:OptIn(InternalObservabilityApi::class)

package com.embabel.agent.spi.support

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.api.event.observation.InternalObservabilityApi
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.ProcessContext
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.support.streaming.CountingChatModel
import com.embabel.agent.spi.support.streaming.chatResponse
import com.embabel.agent.spi.validation.DefaultValidationPromptGenerator
import com.embabel.agent.support.SimpleTestAgent
import com.embabel.agent.test.common.EventSavingAgenticEventListener
import com.embabel.chat.UserMessage
import com.embabel.common.ai.converters.EnumAsOneOf
import com.embabel.common.ai.converters.SchemaGeneratorCustomizer
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.embabel.common.util.EmbabelObjectMapperHolder
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import io.mockk.every
import io.mockk.mockk
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.Executors

/**
 * Checks that [SchemaGeneratorCustomizer]s and [EnumAsOneOf] shape the JSON schema that reaches the model,
 * and that streaming and non-streaming calls send the same schema.
 */
class ChatClientLlmOperationsSchemaCustomizationTest {

    data class Tags(val csv: String)

    data class TaggedDog(val name: String, val tags: Tags)

    @EnumAsOneOf
    enum class Priority {
        @JsonPropertyDescription("Needs same-day response")
        URGENT,
        NORMAL,
    }

    enum class Status { OPEN, CLOSED }

    data class Ticket(val priority: Priority, val status: Status)

    enum class Call { NON_STREAMING, STREAMING, LEGACY_STREAMING }

    private val objectMapper = jacksonObjectMapper()

    private val duke = TaggedDog("Duke", Tags("good,boy"))

    private val ticket = Ticket(Priority.URGENT, Status.OPEN)

    @Nested
    inner class Customizers {

        @ParameterizedTest
        @EnumSource(Call::class)
        fun `without customizers a type keeps its default schema`(call: Call) {
            val schema = schemaSent(call, duke, customizers = emptyList())

            assertEquals("object", schema.at("/properties/tags/type").asText())
        }

        @ParameterizedTest
        @EnumSource(Call::class)
        fun `a customizer changes the schema sent to the model`(call: Call) {
            val schema = schemaSent(call, duke, customizers = listOf(tagsAs("""{"type":"array","items":{"type":"string"}}""")))

            assertEquals("array", schema.at("/properties/tags/type").asText())
            assertEquals("string", schema.at("/properties/tags/items/type").asText())
        }

        @ParameterizedTest
        @EnumSource(Call::class)
        fun `earlier customizers take precedence`(call: Call) {
            val schema = schemaSent(
                call,
                duke,
                customizers = listOf(
                    tagsAs("""{"type":"array","description":"first"}"""),
                    tagsAs("""{"type":"array","description":"second"}"""),
                ),
            )

            assertEquals("first", schema.at("/properties/tags/description").asText())
        }

        @Test
        fun `a customizer changes the schema of createObjectIfPossible`() {
            val fakeChatModel = FakeChatModel("""{"failure":"not enough information"}""")
            val llmOperations = chatClientLlmOperations(
                fakeChatModel,
                customizers = listOf(tagsAs("""{"type":"array","items":{"type":"string"}}""")),
            )

            llmOperations.createObjectIfPossible(
                messages = listOf(UserMessage("Describe the dog")),
                interaction = interaction(),
                outputClass = TaggedDog::class.java,
                agentProcess = agentProcess(),
                action = SimpleTestAgent.actions.first(),
            )

            val tagsSchemas = schemaIn(fakeChatModel.promptsPassed.single()).findValues("tags")
            assertTrue(tagsSchemas.isNotEmpty(), "Schema describes tags")
            assertTrue(tagsSchemas.all { it.path("type").asText() == "array" }, "tags is an array: $tagsSchemas")
        }
    }

    @Nested
    inner class EnumAsOneOfOrdering {

        @ParameterizedTest
        @EnumSource(Call::class)
        fun `EnumAsOneOf constants are described`(call: Call) {
            val schema = schemaSent(call, ticket, customizers = emptyList())

            assertEquals("Needs same-day response", schema.at("/properties/priority/oneOf/0/description").asText())
        }

        @ParameterizedTest
        @EnumSource(Call::class)
        fun `a customizer for enums changes plain enums but not EnumAsOneOf enums`(call: Call) {
            val customizedEnums = SchemaGeneratorCustomizer { builder ->
                builder.forTypesInGeneral().withCustomDefinitionProvider(
                    CustomDefinitionProviderV2 { javaType, context ->
                        if (javaType.erasedType.isEnum) {
                            CustomDefinition(
                                context.generatorConfig.createObjectNode()
                                    .put("type", "string")
                                    .put("description", "customized enum")
                            )
                        } else {
                            null
                        }
                    }
                )
            }

            val schema = schemaSent(call, ticket, customizers = listOf(customizedEnums))

            assertEquals("Needs same-day response", schema.at("/properties/priority/oneOf/0/description").asText())
            assertEquals("customized enum", schema.at("/properties/status/description").asText())
        }
    }

    private fun tagsAs(definition: String) = SchemaGeneratorCustomizer { builder ->
        builder.forTypesInGeneral().withCustomDefinitionProvider(
            CustomDefinitionProviderV2 { javaType, _ ->
                if (javaType.erasedType == Tags::class.java) {
                    CustomDefinition(objectMapper.readTree(definition) as tools.jackson.databind.node.ObjectNode)
                } else {
                    null
                }
            }
        )
    }

    private fun <T : Any> schemaSent(call: Call, response: T, customizers: List<SchemaGeneratorCustomizer>): JsonNode {
        val responseJson = objectMapper.writeValueAsString(response)
        return when (call) {
            Call.NON_STREAMING -> {
                val fakeChatModel = FakeChatModel(responseJson)
                chatClientLlmOperations(fakeChatModel, customizers).createObject(
                    messages = listOf(UserMessage("Describe it")),
                    interaction = interaction(),
                    outputClass = response.javaClass,
                    agentProcess = agentProcess(),
                    action = SimpleTestAgent.actions.first(),
                )
                schemaIn(fakeChatModel.promptsPassed.single())
            }

            Call.STREAMING, Call.LEGACY_STREAMING -> {
                val chatModel = CountingChatModel { Flux.just(chatResponse("$responseJson\n")) }
                val streamed = chatClientLlmOperations(
                    chatModel,
                    customizers,
                    useLegacyStreaming = call == Call.LEGACY_STREAMING,
                )
                    .createStreamingOperations(LlmOptions())
                    .createObjectStream(
                        messages = listOf(UserMessage("Describe it")),
                        interaction = interaction(),
                        outputClass = response.javaClass,
                        agentProcess = agentProcess(),
                        action = SimpleTestAgent.actions.first(),
                    )
                    .collectList()
                    .block()
                assertEquals(listOf(response), streamed)
                schemaIn(chatModel.lastPrompt ?: fail("Model was not called"))
            }
        }
    }

    private fun schemaIn(prompt: Prompt): JsonNode {
        val promptText = prompt.instructions.joinToString("\n") { it.text.orEmpty() }
        val schema = SCHEMA_IN_PROMPT.find(promptText)?.groupValues?.get(1)
            ?: fail("No JSON schema in prompt:\n$promptText")
        return objectMapper.readTree(schema)
    }

    private fun interaction() = LlmInteraction(id = InteractionId("schema-customization"), llm = LlmOptions())

    private fun chatClientLlmOperations(
        chatModel: ChatModel,
        customizers: List<SchemaGeneratorCustomizer>,
        useLegacyStreaming: Boolean = false,
    ): ChatClientLlmOperations {
        val modelProvider = mockk<ModelProvider>()
        every { modelProvider.getLlm(any()) } returns
            SpringAiLlmService("fake", "provider", chatModel, DefaultOptionsConverter)
        return ChatClientLlmOperations(
            modelProvider = modelProvider,
            toolDecorator = DefaultToolDecorator(),
            validator = Validation.buildDefaultValidatorFactory().validator,
            validationPromptGenerator = DefaultValidationPromptGenerator(),
            templateRenderer = JinjavaTemplateRenderer(),
            embabelObjectMapperHolder = EmbabelObjectMapperHolder.createDefault(),
            asyncer = ExecutorAsyncer(Executors.newCachedThreadPool()),
            schemaGeneratorCustomizers = customizers,
            useLegacyStreaming = useLegacyStreaming,
        )
    }

    private fun agentProcess(): AgentProcess {
        val eventListener = EventSavingAgenticEventListener()
        val processContext = mockk<ProcessContext>()
        val agentProcess = mockk<AgentProcess>()
        every { processContext.platformServices } returns mockk()
        every { processContext.platformServices.agentPlatform } returns mockk()
        every { processContext.platformServices.agentPlatform.toolGroupResolver } returns
            RegistryToolGroupResolver("mt", emptyList())
        every { processContext.platformServices.eventListener } returns eventListener
        every { processContext.processOptions } returns ProcessOptions()
        every { processContext.onProcessEvent(any()) } returns Unit
        every { processContext.agentProcess } returns agentProcess
        every { agentProcess.recordLlmInvocation(any()) } returns Unit
        every { agentProcess.agent } returns SimpleTestAgent
        every { agentProcess.processContext } returns processContext
        every { agentProcess.blackboard } returns mockk<Blackboard>(relaxed = true)
        return agentProcess
    }

    private companion object {
        val SCHEMA_IN_PROMPT = Regex("JSON Schema instance[^\n]*\n```(.*?)```", RegexOption.DOT_MATCHES_ALL)
    }
}
