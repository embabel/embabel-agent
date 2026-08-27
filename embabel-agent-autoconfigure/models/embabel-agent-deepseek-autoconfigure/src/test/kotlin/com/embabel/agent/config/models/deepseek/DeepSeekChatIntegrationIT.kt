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
package com.embabel.agent.config.models.deepseek

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.models.DeepSeekModels
import com.embabel.agent.autoconfigure.models.deepseek.AgentDeepSeekAutoConfiguration
import com.embabel.agent.autoconfigure.netty.NettyClientAutoConfiguration
import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

@Profile("deepseek-chat-test")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent"])
@ComponentScan(basePackages = ["com.embabel.agent"])
class DeepSeekChatTestConfig

/**
 * Calls the real DeepSeek API through the platform: a plain call and a tool call, per model.
 *
 * The classpath is part of the fixture. [DeepSeekModelsConfig] builds its own `RestClient` instead
 * of using the shared `aiModelRestClientBuilder`, so Spring picks the client from what is on the
 * classpath — hence the httpclient5 and brotli test dependencies. Without them the request goes out
 * plain and the test passes for the wrong reason.
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=deepseek-v4-pro",
        // One attempt everywhere: a transport failure otherwise arrives buried under a retry storm.
        "embabel.agent.platform.models.deepseek.max-attempts=1",
        "embabel.agent.platform.llm-operations.data-binding.max-attempts=1",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("deepseek-chat-test")
@Import(DeepSeekChatTestConfig::class, AgentDeepSeekAutoConfiguration::class, NettyClientAutoConfiguration::class)
@EnabledIfEnvironmentVariable(
    named = "DEEPSEEK_API_KEY",
    matches = ".+",
    disabledReason = "Integration test requires DEEPSEEK_API_KEY",
)
class DeepSeekChatIntegrationIT(
    @param:Autowired private val ai: Ai,
    @param:Autowired private val llms: List<LlmService<*>>,
    @param:Autowired private val applicationContext: ApplicationContext,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Without this bean the calls below would exercise the fallback client, not the shared one. */
    @Test
    fun `the shared client builder is the one under test`() {
        assertTrue(applicationContext.containsBean("aiModelRestClientBuilder"), "Expected the shared builder")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("models")
    fun `registers the model`(model: String) {
        assertNotNull(llms.find { it.name == model }, "Expected $model to be registered, have ${llms.map { it.name }}")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("models")
    fun `plain call reaches the model`(model: String) {
        val response = generate(model, prompt = READY_PROMPT, tools = null)

        assertTrue(response.contains("READY", ignoreCase = true), "Expected READY from $model, got: $response")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("models")
    fun `call with a tool reaches the model`(model: String) {
        val tools = AddTool()

        val response = generate(model, prompt = ADD_PROMPT, tools = tools)

        assertTrue(tools.called, "Expected $model to call the add tool; it answered: $response")
        assertTrue(response.contains("42"), "Expected 42 from $model, got: $response")
    }

    /**
     * The control: when the tests above are red, this one says whether the API is down or the answer
     * merely came back unreadable. It asserts nothing about the negotiated call — this is a raw
     * client, not the platform's, so it would keep advertising brotli long after the fix.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("models")
    fun `the endpoint itself answers something readable`(model: String) {
        val negotiated = runCatching { chatCompletion(model, acceptEncoding = null) }
        val uncompressed = runCatching { chatCompletion(model, acceptEncoding = "identity") }

        logger.info(
            "Raw DeepSeek call to {}: default headers -> {}, Accept-Encoding: identity -> {}",
            model,
            negotiated.fold({ "read ${it.size} field(s)" }, { "unreadable: $it" }),
            uncompressed.fold({ "read ${it.size} field(s)" }, { "unreadable: $it" }),
        )

        assertTrue(
            uncompressed.getOrNull()?.containsKey("choices") == true,
            "The uncompressed response itself is unreadable, so this is not about content encoding: " +
                "${uncompressed.exceptionOrNull()}",
        )
    }

    private fun generate(model: String, prompt: String, tools: Any?): String {
        val runner = ai.withLlm(LlmOptions.withModel(model)).let { if (tools == null) it else it.withToolObject(tools) }
        return try {
            runner.generateText(prompt).trim()
        } catch (ex: Exception) {
            throw AssertionError("Call to $model failed: ${causeChain(ex)}", ex)
        }
    }

    /** The provider error arrives wrapped by the retry and data-binding layers. */
    private fun causeChain(ex: Throwable): String =
        generateSequence<Throwable>(ex) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    private fun chatCompletion(model: String, acceptEncoding: String?): Map<String, Any> =
        RestClient.create()
            .post()
            .uri("https://api.deepseek.com/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .headers { headers ->
                headers.setBearerAuth(System.getenv("DEEPSEEK_API_KEY"))
                acceptEncoding?.let { headers.set("Accept-Encoding", it) }
            }
            .body(
                """
                {"model": "$model",
                 "messages": [{"role": "user", "content": "$READY_PROMPT"}],
                 "max_tokens": 16}
                """.trimIndent()
            )
            .retrieve()
            .body<Map<String, Any>>()!!

    /** One tool, one argument, one obvious answer: what is under test is the call, not the model. */
    class AddTool {

        @Volatile
        var called = false

        @LlmTool(description = "Add two numbers. Always use this rather than adding them yourself.")
        fun add(a: Int, b: Int): Int {
            called = true
            return a + b
        }
    }

    companion object {
        private const val READY_PROMPT = "Reply with exactly the word READY."
        private const val ADD_PROMPT = "Use the add tool to compute 20 + 22, then reply with the number only."

        /**
         * Every model this provider registers, so a failure belongs to the provider or to one model
         * rather than being guessed at. The two deprecated names are included on purpose: the day
         * DeepSeek retires them, a refusal reads differently from an unreadable answer.
         */
        @JvmStatic
        @Suppress("DEPRECATION")
        fun models(): List<String> = listOf(
            DeepSeekModels.DEEPSEEK_V4_PRO,
            DeepSeekModels.DEEPSEEK_V4_FLASH,
            DeepSeekModels.DEEPSEEK_CHAT,
            DeepSeekModels.DEEPSEEK_REASONER,
        )
    }
}
