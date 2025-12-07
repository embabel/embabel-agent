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
package com.embabel.agent.config.models.openai;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.api.common.streaming.StreamingPromptRunnerOperations;
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration;
import com.embabel.common.ai.model.Llm;
import com.embabel.common.core.streaming.StreamingCapability;
import com.embabel.common.core.streaming.StreamingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java integration test for OpenAI streaming functionality using builder pattern.
 * Tests the Java equivalent of Kotlin's asStreaming() extension function.
 */
@SpringBootTest(
    properties = {
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
@Import({StreamingTestConfig.class, AgentOpenAiAutoConfiguration.class})
class LLMOpenAiStreamingBuilderIT {

    private static final Logger logger = LoggerFactory.getLogger(LLMOpenAiStreamingBuilderIT.class);

    @Autowired
    private Autonomy autonomy;

    @Autowired 
    private Ai ai;

    @Autowired
    private List<Llm> llms;

    /**
     * Simple data class for testing streaming object creation
     */
    public static class MonthItem {
        private String name;

        public MonthItem() {}

        public MonthItem(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /**
         * Builder pattern to provide Java equivalent of Kotlin's asStreaming() extension function.
         * Solves the problem that Java cannot directly call Kotlin extension functions.
         */
        public record StreamingPromptRunnerBuilder(PromptRunner runner) {

        /**
             * Java equivalent of Kotlin's asStreaming() extension function.
             * Provides type-safe access to streaming operations.
             */
            public StreamingPromptRunnerOperations asStream() {
                if (!runner.supportsStreaming()) {
                    throw new UnsupportedOperationException(
                            "This LLM does not support streaming: " + Objects.requireNonNull(runner.getLlm()).getCriteria()
                    );
                }

                StreamingCapability capability = runner.stream();
                if (capability instanceof StreamingPromptRunnerOperations) {
                    return (StreamingPromptRunnerOperations) capability;
                }

                throw new IllegalStateException(
                        "Unexpected streaming capability implementation: " + capability.getClass()
                );
            }
        }

    @Test
    void realStreamingIntegrationWithReactiveCallbacks() {
        // Enable Reactor debugging
        reactor.util.Loggers.useVerboseConsoleLoggers();

        // Given: Use the existing streaming test LLM (configured as "best")
        PromptRunner runner = ai.withLlm("gpt-4.1-mini");
        assertTrue(runner.supportsStreaming(), "Test LLM should support streaming");

        // When: Subscribe with real reactive callbacks using builder pattern
        List<String> receivedEvents = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> errorOccurred = new AtomicReference<>();
        AtomicBoolean completionCalled = new AtomicBoolean(false);

        String prompt = "What are two the most hottest months in Florida.";

        // Use StreamingPromptBuilder instead of Kotlin extension function
        Flux<StreamingEvent<MonthItem>> results = new StreamingPromptRunnerBuilder(runner)
            .asStream()
            .withPrompt(prompt)
            .createObjectStreamWithThinking(MonthItem.class);

        results
            .timeout(Duration.ofSeconds(30))
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
            .blockLast(Duration.ofSeconds(600));

        // Then: Verify real integration streaming behavior
        assertNull(errorOccurred.get(), "Integration streaming should not produce errors");
        assertTrue(completionCalled.get(), "Integration stream should complete successfully");
        assertFalse(receivedEvents.isEmpty(), "Should receive object events");

        logger.info("Integration streaming test completed successfully with {} total events", receivedEvents.size());
    }
}