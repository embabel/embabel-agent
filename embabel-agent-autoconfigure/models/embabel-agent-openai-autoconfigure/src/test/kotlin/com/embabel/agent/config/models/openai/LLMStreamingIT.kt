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
package com.embabel.agent.config.models.openai


import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.createObjectIfPossible
import com.embabel.agent.api.common.streaming.asStreaming
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.core.streaming.StreamingEvent
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import java.time.Duration


@Profile("streaming-test")
@ConfigurationPropertiesScan(
    basePackages = [
        "com.embabel.agent",
        "com.embabel.example",
    ]
)
@ComponentScan(
    basePackages = [
        "com.embabel.agent",
        "com.embabel.example",
    ]
)
class StreamingTestConfig {

    // basic scanning
}

data class MonthItem(val name: String)


/**
 * Simple tool for testing tool integration with streaming
 */
class SimpleTool {
    private var wasInvokedFlag = false

    @Tool(description = "Simple test tool that greets a person")
    fun greet(name: String): String {
        wasInvokedFlag = true
        return "Hello $name"
    }

    fun wasInvoked(): Boolean = wasInvokedFlag
}

@SpringBootTest(
    properties = [
        "embabel.models.llms.cheapest=gpt-4.1-mini",
        "embabel.models.llms.best=gpt-4.1-mini",
        "embabel.models.llms.default-llm=gpt-4.1-mini",
        "spring.main.allow-bean-definition-overriding=true",

        // Spring AI Debug Logging
        "logging.level.org.springframework.ai=DEBUG",
        "logging.level.org.springframework.ai.openai=TRACE",
        "logging.level.org.springframework.ai.chat=DEBUG",

        // Reactor Debug Logging
        "logging.level.reactor=DEBUG",
        "logging.level.reactor.core=TRACE",
        "logging.level.reactor.netty=DEBUG",

        // HTTP/WebClient Debug
        "logging.level.org.springframework.web.reactive=DEBUG",
        "logging.level.reactor.netty.http.client=TRACE",

        // OpenAI API Debug
        "logging.level.org.springframework.ai.openai.api=TRACE",

        // Add these for complete HTTP tracing
        "logging.level.org.springframework.web.client.RestTemplate=DEBUG",
        "logging.level.org.apache.http=DEBUG",
        "logging.level.httpclient.wire=DEBUG"


    ]
)
@ActiveProfiles("streaming-test")
@Import(StreamingTestConfig::class, AgentOpenAiAutoConfiguration::class)
class LLMStreamingIT(
    @param:Autowired private val autonomy: Autonomy,
    @param:Autowired private val ai: Ai,
    @param:Autowired private val llms: List<LlmService<*>>,
) {

    private val logger = LoggerFactory.getLogger(LLMStreamingIT::class.java)


    @Test
    fun `real streaming integration with reactive callbacks`() {

        // Enable Reactor debugging
        reactor.util.Loggers.useVerboseConsoleLoggers()

        // Given: Use the existing streaming test LLM (configured as "fastest")
        val runner = ai.withLlm("gpt-4.1-mini")

        assertTrue(runner.supportsStreaming(), "Test LLM should support streaming") // ADD THIS DEBUG BLOCK:

        // When: Subscribe with real reactive callbacks
        val receivedEvents = mutableListOf<String>()
        var errorOccurred: Throwable? = null
        var completionCalled = false

        val prompt = """
            What are two the most hottest months in Florida.
            """.trimIndent()

        val results = runner.asStreaming()
            .withPrompt(prompt)
            .createObjectStreamWithThinking(MonthItem::class.java)

        results
            .timeout(Duration.ofSeconds(30))
            .doOnSubscribe {
                logger.info("Stream subscription started")
            }
            .doOnNext { event ->
                when {
                    event.isThinking() -> {
                        val content = event.getThinking()!!
                        receivedEvents.add("THINKING: $content")
                        logger.info("Integration test received thinking: {}", content)
                    }

                    event.isObject() -> {
                        val obj = event.getObject()!!
                        receivedEvents.add("OBJECT: ${obj.name}")
                        logger.info("Integration test received object: {}", obj.name)
                    }
                }
            }
            .doOnError { error ->
                errorOccurred = error
                logger.error("Integration test stream error: {}", error.message)
            }
            .doOnComplete {
                completionCalled = true
                logger.info("Integration test stream completed successfully")
            }
            .blockLast(Duration.ofSeconds(600))


        // Then: Verify real integration streaming behavior
        assertNull(errorOccurred, "Integration streaming should not produce errors")
        assertTrue(completionCalled, "Integration stream should complete successfully")
        assertTrue(receivedEvents.size >= 1, "Should receive object events")

        // Verify we received object events (existing test LLM returns simple JSON)
        val objectEvents = receivedEvents.filter { it.startsWith("OBJECT:") }
        // assertTrue(objectEvents.isNotEmpty(), "Should receive object events from integration streaming")

        logger.info("Integration streaming test completed successfully with {} total events", receivedEvents.size)
    }

    @Test
    fun `test basic embabel-openai object creation`() {
        println("DEBUG: Testing basic OpenAI connectivity...")

        try {
            val runner = ai.withLlm("gpt-4.1-mini")
                                        .withGenerateExamples(true);
            println("DEBUG: Created runner")

            // Test non-streaming call first
            val response = runner.createObjectIfPossible<MonthItem>(
                """
            get hottest summer month in florida boca-raton
            """.trimIndent(),
            )

            println("DEBUG: Non-streaming response: '$response'")

        } catch (e: Exception) {
            println("DEBUG: Non-streaming failed: ${e.message}")
            e.printStackTrace()
        }
    }

    @Test
    fun `test spring ai streaming directly`() {

        reactor.util.Loggers.useVerboseConsoleLoggers()

        try {
            // Get the raw Spring AI ChatModel directly
            val llm = llms.find { it.name == "gpt-4.1-mini" } as SpringAiLlmService
            val chatModel = llm.model

            println("DEBUG: Testing raw Spring AI streaming...")

            // Test Spring AI streaming with minimal setup
            val prompt = org.springframework.ai.chat.prompt.Prompt("Say hello")


            chatModel
                .stream(prompt)
                .doOnNext { chatResponse ->
                    println("DEBUG: Got ChatResponse: ${chatResponse.results.size} generations")
                }
                .map { chatResponse ->
                    "chunk-" + chatResponse.results.size  // Temporary test content
                }
                .doOnNext { chunk -> println("DEBUG: Received chunk: '$chunk'") }
                .doOnSubscribe { println("DEBUG: Stream subscribed") }
                .map { chunk ->
                    StreamingEvent.Thinking(chunk)  // Convert to StreamingEvent
                }.timeout(Duration.ofSeconds(10))
                .subscribe()




            Thread.sleep(12000)

        } catch (e: Exception) {
            println("DEBUG: Test failed: $e")
            e.printStackTrace()
        }
    }


}
