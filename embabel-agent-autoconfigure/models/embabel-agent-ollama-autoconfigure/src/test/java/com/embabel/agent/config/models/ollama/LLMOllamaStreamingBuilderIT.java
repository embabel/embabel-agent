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
package com.embabel.agent.config.models.ollama;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.api.streaming.StreamingPromptRunnerBuilder;
import com.embabel.agent.autoconfigure.models.ollama.AgentOllamaAutoConfiguration;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.Thinking;
import com.embabel.common.core.streaming.StreamingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java integration test for Ollama streaming functionality using builder pattern.
 * Tests the Java equivalent of Kotlin's asStreaming() extension function.
 */
@SpringBootTest(
        properties = {
                "embabel.models.cheapest=qwen3:latest",
                "embabel.models.best=qwen3:latest",
                "embabel.models.default-llm=qwen3:latest",
                "spring.main.allow-bean-definition-overriding=true",

                // Streaming Infrastructure logging
                "logging.level.com.embabel.agent.spi.support.streaming.StreamingLlmOperationsImpl=TRACE",

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

                // Complete HTTP tracing
                "logging.level.org.springframework.web.client.RestTemplate=DEBUG",
                "logging.level.org.apache.http=DEBUG",
                "logging.level.httpclient.wire=DEBUG"
        }
)
@ActiveProfiles("streaming-test")
@ConfigurationPropertiesScan(
        basePackages = {
                "com.embabel.agent",
                "com.embabel.example"
        }
)
@ComponentScan(
        basePackages = {
                "com.embabel.agent",
                "com.embabel.example"
        },
        excludeFilters = {
                @ComponentScan.Filter(
                        type = org.springframework.context.annotation.FilterType.REGEX,
                        pattern = ".*GlobalExceptionHandler.*"
                )
        }
)
@Import({AgentOllamaAutoConfiguration.class})
class LLMOllamaStreamingBuilderIT {

    private static final Logger logger = LoggerFactory.getLogger(LLMOllamaStreamingBuilderIT.class);

    @Autowired
    private Autonomy autonomy;

    @Autowired
    private Ai ai;

    @Autowired
    private List<LlmService<?>> llms;

    /**
     * Simple data class for testing streaming object creation
     */
    static class MonthItem {
        private String name;

        private Short temperature;

        public MonthItem() {
        }

        public MonthItem(String name) {
            this.name = name;
        }

        public MonthItem(String name, Short temperature) {
            this.name = name;
            this.temperature = temperature;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Short getTemperature() {
            return temperature;
        }

        public void setTemperature(Short temperature) {
            this.temperature = temperature;
        }
    }

    /**
     * Tool
     */
    static class Tooling {

        @Tool
        Short convertFromCelsiusToFahrenheit(Short inputTemp) {
            return (short) ((inputTemp * 2) +32);
        }
    }

    @Test
    void realStreamingOllamaIntegrationWithReactiveCallbacks() {
        // Enable Reactor debugging
        reactor.util.Loggers.useVerboseConsoleLoggers();

        // Given: Use the existing streaming test LLM (configured as "best")
        PromptRunner runner = ai.withLlm("qwen3:latest")
                                .withToolObject(new Tooling());
        assertTrue(runner.supportsStreaming(), "Test LLM should support streaming");

        // When: Subscribe with real reactive callbacks using builder pattern
        List<String> receivedEvents = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errorOccurred = new AtomicReference<>();
        AtomicBoolean completionCalled = new AtomicBoolean(false);

        String prompt = "What are exactly two the most hottest months in Florida and their respective highest temperatures";

        // Use StreamingPromptBuilder instead of Kotlin extension function
        Flux<StreamingEvent<MonthItem>> results = new StreamingPromptRunnerBuilder(runner)
                .streaming()
                .withPrompt(prompt)
                .createObjectStreamWithThinking(MonthItem.class);

        results
                .timeout(Duration.ofSeconds(150))
                .doOnSubscribe(subscription -> {
                    logger.info("Stream subscription started");
                })
                .doOnNext(event -> {
                    if (event.isThinking()) {
                        String content = event.getThinking();
                        receivedEvents.add("THINKING: " + content);
                        logger.info("Integration test received thinking: {}", content);
                    } else if (event.isObject()) {
                        MonthItem obj = event.getObject();
                        receivedEvents.add("OBJECT: " + obj.getName());
                        logger.info("Integration test received object: {}", obj.getName());
                    }
                })
                .doOnError(error -> {
                    errorOccurred.set(error);
                    logger.error("Integration test stream error: {}", error.getMessage());
                })
                .doOnComplete(() -> {
                    completionCalled.set(true);
                    logger.info("Integration test stream completed successfully");
                })
                .blockLast(Duration.ofSeconds(6000));

        // Then: Verify real integration streaming behavior
        assertNull(errorOccurred.get(), "Integration streaming should not produce errors");
        assertTrue(completionCalled.get(), "Integration stream should complete successfully");
        assertFalse(receivedEvents.isEmpty(), "Should receive object events");

        logger.info("Integration streaming test completed successfully with {} total events", receivedEvents.size());
    }

    /**
     * Validates application-level thinking retrieval vs provider token budget (#1799 / #1853).
     * <p>
     * {@code createObjectStreamWithThinking} must enable thinking extraction/format via
     * {@code Thinking.extractThinking} without requiring {@code Thinking.withTokenBudget}.
     * Object-only streaming with a provider budget alone must still complete (budget is orthogonal).
     */
    @Test
    void createObjectStreamWithThinkingRetrievalIndependentOfTokenBudget() {
        reactor.util.Loggers.useVerboseConsoleLoggers();

        // --- Path A: *WithThinking* without any LlmOptions thinking / token budget ---
        // Format instructions and extractThinking are applied by createObjectStreamWithThinking.
        PromptRunner extractionOnlyRunner = ai.withLlm("qwen3:latest")
                .withToolObject(new Tooling());
        assertTrue(extractionOnlyRunner.supportsStreaming(), "Test LLM should support streaming");

        List<String> extractionEvents = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> extractionError = new AtomicReference<>();
        AtomicBoolean extractionCompleted = new AtomicBoolean(false);

        String thinkingPrompt =
                "What are exactly two of the hottest months in Florida and their highest temperatures. "
                        + "Think step by step before returning the JSONL objects.";

        Flux<StreamingEvent<MonthItem>> extractionStream = new StreamingPromptRunnerBuilder(extractionOnlyRunner)
                .streaming()
                .withPrompt(thinkingPrompt)
                .createObjectStreamWithThinking(MonthItem.class);

        extractionStream
                .timeout(Duration.ofSeconds(150))
                .doOnNext(event -> {
                    if (event.isThinking()) {
                        extractionEvents.add("THINKING: " + event.getThinking());
                        logger.info("Extraction-only path thinking: {}", event.getThinking());
                    } else if (event.isObject()) {
                        MonthItem obj = event.getObject();
                        extractionEvents.add("OBJECT: " + obj.getName());
                        logger.info("Extraction-only path object: {}", obj.getName());
                    }
                })
                .doOnError(error -> {
                    extractionError.set(error);
                    logger.error("Extraction-only stream error: {}", error.getMessage());
                })
                .doOnComplete(() -> extractionCompleted.set(true))
                .blockLast(Duration.ofSeconds(6000));

        assertNull(extractionError.get(), "createObjectStreamWithThinking must work without tokenBudget");
        assertTrue(extractionCompleted.get(), "Extraction-only stream should complete");
        assertFalse(extractionEvents.isEmpty(), "Should receive at least one streaming event without tokenBudget");
        assertTrue(
                extractionEvents.stream().anyMatch(e -> e.startsWith("OBJECT:")),
                "Extraction-only path should still produce object events"
        );
        logger.info(
                "Extraction-only createObjectStreamWithThinking completed with {} events (thinking events may vary by model)",
                extractionEvents.size()
        );

        // --- Path B: *WithThinking* with provider token budget (budget preserved + extractThinking applied) ---
        PromptRunner budgetAndExtractionRunner = ai.withLlm(
                        LlmOptions.withModel("qwen3:latest")
                                .withThinking(Thinking.withTokenBudget(100)))
                .withToolObject(new Tooling());
        assertTrue(budgetAndExtractionRunner.supportsStreaming(), "Budget-configured LLM should support streaming");

        List<String> budgetEvents = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> budgetError = new AtomicReference<>();
        AtomicBoolean budgetCompleted = new AtomicBoolean(false);

        Flux<StreamingEvent<MonthItem>> budgetStream = new StreamingPromptRunnerBuilder(budgetAndExtractionRunner)
                .streaming()
                .withPrompt(thinkingPrompt)
                .createObjectStreamWithThinking(MonthItem.class);

        budgetStream
                .timeout(Duration.ofSeconds(150))
                .doOnNext(event -> {
                    if (event.isThinking()) {
                        budgetEvents.add("THINKING: " + event.getThinking());
                        logger.info("Budget+extraction path thinking: {}", event.getThinking());
                    } else if (event.isObject()) {
                        MonthItem obj = event.getObject();
                        budgetEvents.add("OBJECT: " + obj.getName());
                        logger.info("Budget+extraction path object: {}", obj.getName());
                    }
                })
                .doOnError(error -> {
                    budgetError.set(error);
                    logger.error("Budget+extraction stream error: {}", error.getMessage());
                })
                .doOnComplete(() -> budgetCompleted.set(true))
                .blockLast(Duration.ofSeconds(6000));

        assertNull(budgetError.get(), "createObjectStreamWithThinking must work with tokenBudget (applyExtraction)");
        assertTrue(budgetCompleted.get(), "Budget+extraction stream should complete");
        assertFalse(budgetEvents.isEmpty(), "Should receive events when tokenBudget coexists with extractThinking");
        assertTrue(
                budgetEvents.stream().anyMatch(e -> e.startsWith("OBJECT:")),
                "Budget+extraction path should still produce object events"
        );
        logger.info(
                "Budget+extraction createObjectStreamWithThinking completed with {} events",
                budgetEvents.size()
        );

        // --- Path C: object-only stream with provider budget only (no *WithThinking*) ---
        // Token budget alone must not require thinking events; stream of typed objects completes.
        PromptRunner budgetOnlyRunner = ai.withLlm(
                        LlmOptions.withModel("qwen3:latest")
                                .withThinking(Thinking.withTokenBudget(100)))
                .withToolObject(new Tooling());

        List<String> objectOnlyEvents = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> objectOnlyError = new AtomicReference<>();
        AtomicBoolean objectOnlyCompleted = new AtomicBoolean(false);

        String objectOnlyPrompt =
                "Return exactly two JSONL objects for the hottest months in Florida with name and temperature.";

        Flux<MonthItem> objectOnlyStream = new StreamingPromptRunnerBuilder(budgetOnlyRunner)
                .streaming()
                .withPrompt(objectOnlyPrompt)
                .createObjectStream(MonthItem.class);

        objectOnlyStream
                .timeout(Duration.ofSeconds(150))
                .doOnNext(obj -> {
                    objectOnlyEvents.add("OBJECT: " + obj.getName());
                    logger.info("Object-only + budget path object: {}", obj.getName());
                })
                .doOnError(error -> {
                    objectOnlyError.set(error);
                    logger.error("Object-only stream error: {}", error.getMessage());
                })
                .doOnComplete(() -> objectOnlyCompleted.set(true))
                .blockLast(Duration.ofSeconds(6000));

        assertNull(objectOnlyError.get(), "Object-only stream with tokenBudget should not error");
        assertTrue(objectOnlyCompleted.get(), "Object-only stream should complete");
        assertFalse(objectOnlyEvents.isEmpty(), "Object-only path should receive typed objects");
        logger.info(
                "Object-only stream with tokenBudget completed with {} objects (thinking retrieval independent of budget)",
                objectOnlyEvents.size()
        );
    }

    @Test
    void rawTextStreamingOllamaIntegrationWithReactiveCallbacks() {
        // Enable Reactor debugging
        reactor.util.Loggers.useVerboseConsoleLoggers();

        // Given: Use the existing streaming test LLM (configured as "best")
        PromptRunner runner = ai.withLlm("qwen3:latest");
        assertTrue(runner.supportsStreaming(), "Test LLM should support streaming");

        // When: Subscribe with real reactive callbacks using builder pattern
        List<String> receivedTextChunks = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errorOccurred = new AtomicReference<>();
        AtomicBoolean completionCalled = new AtomicBoolean(false);

        String prompt = "What is the highest building in Paris?";

        // Use StreamingPromptBuilder instead of Kotlin extension function
        Flux<String> results = new StreamingPromptRunnerBuilder(runner)
                .streaming()
                .withPrompt(prompt)
                .generateStream();

        // Subscribe with real reactive callbacks using builder pattern
        results
                .timeout(Duration.ofSeconds(150))
                .doOnSubscribe(subscription -> {
                    logger.info("Stream subscription started");
                })
                .doOnNext(content -> {
                    receivedTextChunks.add(content);
                    logger.info("Integration test received text chunk: {}", content);
                })
                .doOnError(error -> {
                    errorOccurred.set(error);
                    logger.error("Integration test stream error: {}", error.getMessage());
                })
                .doOnComplete(() -> {
                    completionCalled.set(true);
                    logger.info("Integration test stream completed successfully");
                })
                .blockLast(Duration.ofSeconds(6000));

        // Then: Verify real integration streaming behavior
        assertNull(errorOccurred.get(), "Integration streaming should not produce errors");
        assertTrue(completionCalled.get(), "Integration stream should complete successfully");
        assertFalse(receivedTextChunks.isEmpty(), "Should receive text chunks");

        logger.info("Integration streaming test completed successfully with {} total text chunks", receivedTextChunks.size());
    }

    @Test
    void testSpringOllamaStreamingDirectly() {
        reactor.util.Loggers.useVerboseConsoleLoggers();

        List<String> receivedEvents = new CopyOnWriteArrayList<>();

        try {
            // Get the raw Spring AI ChatModel directly
            SpringAiLlmService springAiLlm = llms.stream()
                    .filter(l -> "qwen3:latest".equals(l.getName()))
                    .filter(l -> l instanceof SpringAiLlmService)
                    .map(l -> (SpringAiLlmService) l)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("LLM qwen3:latest not found"));

            org.springframework.ai.chat.model.ChatModel chatModel = springAiLlm.getModel();


            System.out.println("DEBUG: Testing raw Spring AI streaming...");

            // Test Spring AI streaming with minimal setup
            org.springframework.ai.chat.prompt.Prompt prompt =
                    new org.springframework.ai.chat.prompt.Prompt("Say hello");

            ((org.springframework.ai.chat.model.ChatModel) chatModel)
                    .stream(prompt)
                    .doOnNext(chatResponse -> {
                        receivedEvents.add( chatResponse.getResults().toString());
                        System.out.println("DEBUG: Got ChatResponse: " +
                                chatResponse.getResults().size() + " generations");
                    })
                    .map(chatResponse ->
                            "chunk-" + chatResponse.getResults()
                    )
                    .doOnNext(chunk ->
                            System.out.println("DEBUG: Received chunk: '" + chunk +
                                    "'")
                    )
                    .doOnSubscribe(subscription ->
                            System.out.println("DEBUG: Stream subscribed")
                    )
                    .timeout(Duration.ofSeconds(10))
                    .subscribe();
            //NOSONAR
            Thread.sleep(12000);

            assertFalse(receivedEvents.isEmpty());

        } catch (Exception e) {
            System.out.println("DEBUG: Test failed: " + e);
        }
    }


}