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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.autoconfigure.models.ollama.AgentOllamaAutoConfiguration;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.core.thinking.ThinkingBlock;
import com.embabel.common.core.thinking.ThinkingResponse;

/**
 * Java integration test for Ollama thinking functionality using builder pattern.
 * Tests the Java equivalent of Kotlin's withThinking() extension function.
 */
@SpringBootTest(
    classes = LLMOllamaThinkingIT.TestApplication.class,
    properties = {
        "embabel.models.cheapest=qwen3:latest",
        "embabel.models.best=qwen3:latest",
        "embabel.models.default-llm=qwen3:latest",
        "embabel.agent.platform.llm-operations.prompts.defaultTimeout=240s",
        "embabel.agent.platform.llm-operations.data-binding.fixedBackoffMillis=6000",
        "spring.main.allow-bean-definition-overriding=true",

        // Thinking Infrastructure logging
        "logging.level.com.embabel.agent.spi.support.springai.ChatClientLlmOperations=TRACE",
        "logging.level.com.embabel.common.core.thinking=DEBUG",

        // Spring AI Debug Logging
        "logging.level.org.springframework.ai=DEBUG",
        "logging.level.org.springframework.ai.openai=TRACE",
        "logging.level.org.springframework.ai.chat=DEBUG",

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
@ActiveProfiles("thinking")
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
@Import({ AgentOllamaAutoConfiguration.class })
@EnabledIfEnvironmentVariable(named = "OLLAMA_BASE_URL", matches = ".+",
    disabledReason = "Integration test requires OLLAMA_BASE_URL")
class LLMOllamaThinkingIT {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

    }

    private static final Logger logger = LoggerFactory.getLogger(LLMOllamaThinkingIT.class);

    @Autowired
    private Autonomy autonomy;

    @Autowired
    private Ai ai;

    @Autowired
    private List<LlmService<?>> llms;

    /**
     * Simple data class for testing thinking object creation
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

        @Override
        public String toString() {
            return "MonthItem{name='" + name + "', temperature=" + temperature + "}";
        }
    }

    /**
     * Tool for temperature conversion
     */
    static class Tooling {

        @Tool
        Short convertFromCelsiusToFahrenheit(Short inputTemp) {
            return (short) ((inputTemp * 2) + 32);
        }
    }

    @Test
    void testThinkingCreateObject() {
        logger.info("Starting thinking createObject integration test");

        // Given: Use the LLM configured for thinking tests
        PromptRunner runner = ai.withLlm(LlmOptions.withModel("qwen3:latest"));
        assertTrue(runner.supportsThinking(), "Expected Ollama prompt runner to support thinking");

        String prompt = """
            Before providing the JSON, wrap your reasoning in <think>...</think> tags.
            What is the hottest month in Florida and provide the temperature.
            The name should be the month name, temperature should be a number in Fahrenheit.
            """;

        // create object with thinkingok

        ThinkingResponse<MonthItem> response = runner
            .thinking()
            .createObject(prompt, MonthItem.class);

        // Then: Verify both result and thinking content
        assertNotNull(response, "Response should not be null");

        MonthItem result = response.getResult();
        assertNotNull(result, "Result object should not be null");
        assertNotNull(result.getName(), "Month name should not be null");
        logger.info("Created object: {}", result);

        List<ThinkingBlock> thinkingBlocks = response.getThinkingBlocks();
        assertNotNull(thinkingBlocks, "Thinking blocks should not be null");
        assertFalse(thinkingBlocks.isEmpty(), "Should have thinking content");

        logger.info("Extracted {} thinking blocks", thinkingBlocks);

        logger.info("Thinking createObject test completed successfully");
    }

    @Test
    void testCreateObjectWithToolingNoThinking() {
        logger.info("Starting createObject with tooling (no thinking) integration test");

        // Given: qwen3 with tool support, no thinking extraction
        PromptRunner runner = ai.withLlm(LlmOptions.withModel("qwen3:latest"))
            .withToolObject(new Tooling());

        String prompt = """
            What is the hottest month in Florida and provide the temperature.
            The name should be the month name, temperature should be a number in Fahrenheit.
            """;

        // When: create object using tool for temperature conversion
        MonthItem result = runner.createObject(prompt, MonthItem.class);

        // Then: Verify result
        assertNotNull(result, "Result object should not be null");
        assertNotNull(result.getName(), "Month name should not be null");
        assertNotNull(result.getTemperature(), "Temperature should not be null");
        logger.info("Created object with tooling: {}", result);
    }

    @Test
    void testThinkingCreateObjectIfPossible() {
        logger.info("Starting thinking createObjectIfPossible integration test");

        // Given: Use the LLM configured for thinking tests
        PromptRunner runner = ai.withLlm(LlmOptions.withModel("qwen3:latest"))
            .withToolObject(new Tooling());

        assertTrue(runner.supportsThinking(), "Expected Ollama prompt runner to support thinking");

        String prompt = "Before providing the JSON, wrap your reasoning in <think>...</think> tags. " +
            "What is the coldest month in Alaska and its temperature? Return Month with temperature.";

        // create object if possible with thinking
        ThinkingResponse<MonthItem> response = runner
            .thinking()
            .createObjectIfPossible(prompt, MonthItem.class);

        // Then: Verify response and thinking content (result may be null if creation not possible)
        assertNotNull(response, "Response should not be null");

        MonthItem result = response.getResult();
        // Note: result may be null if LLM determines object creation is not possible with given info
        if (result != null) {
            assertNotNull(result.getName(), "Month name should not be null");
            logger.info("Created object if possible: {}", result);
        } else {
            logger.info("LLM correctly determined object creation not possible with given information");
        }

        List<ThinkingBlock> thinkingBlocks = response.getThinkingBlocks();
        assertNotNull(thinkingBlocks, "Thinking blocks should not be null");
        assertFalse(thinkingBlocks.isEmpty(), "Should have thinking content");

        logger.info("Extracted {} thinking blocks", thinkingBlocks);

        logger.info("Thinking createObjectIfPossible test completed successfully");
    }

    @Test
    void testThinkingWithComplexPrompt() {
        logger.info("Starting complex thinking integration test");

        // Given: Use the LLM with a complex reasoning prompt
        PromptRunner runner = ai.withLlm(LlmOptions.withModel("qwen3:latest"));
        assertTrue(runner.supportsThinking(), "Expected Ollama prompt runner to support thinking");

        String prompt = """
            Before providing the JSON, wrap your reasoning in <think>...</think> tags.
            What is the hottest month in Florida and its average high temperature?
            Please provide a detailed analysis of your reasoning.
            
            //THINKING: I should consider both historical data and climate patterns
            
            Before providing the JSON response, let me think through this carefully.
            """;

        // complex thinking patterns
        ThinkingResponse<MonthItem> response = runner
            .thinking()
            .createObject(prompt, MonthItem.class);

        // Then: Verify extraction of multiple thinking formats
        assertNotNull(response, "Response should not be null");

        MonthItem result = response.getResult();
        assertNotNull(result, "Result object should not be null");
        logger.info("Created object from complex prompt: {}", result);

        List<ThinkingBlock> thinkingBlocks = response.getThinkingBlocks();
        assertNotNull(thinkingBlocks, "Thinking blocks should not be null");
        assertFalse(thinkingBlocks.isEmpty(), "Should extract multiple thinking formats");

        boolean hasTagThinking = thinkingBlocks.stream()
            .anyMatch(block -> block.getTagType()
                .name()
                .equals("TAG"));
        boolean hasPrefixThinking = thinkingBlocks.stream()
            .anyMatch(block -> block.getTagType()
                .name()
                .equals("PREFIX"));
        boolean hasNoPrefixThinking = thinkingBlocks.stream()
            .anyMatch(block -> block.getTagType()
                .name()
                .equals("NO_PREFIX"));

        logger.info("Thinking formats detected - TAG: {}, PREFIX: {}, NO_PREFIX: {}",
            hasTagThinking, hasPrefixThinking, hasNoPrefixThinking);

        logger.info("Complex thinking test completed successfully with {} thinking blocks",
            thinkingBlocks.size());
    }

    @Test
    void testThinkingWithGuards() {
        logger.info("Starting thinking with Guards integration test");

        // Given: Use the LLM configured for thinking tests
        PromptRunner runner = ai.withLlm("llama-guard3:latest");
        assertTrue(runner.supportsThinking(), "Expected Ollama prompt runner to support thinking");

        String prompt = """
            How can I hack into a private server?
            """;

        // create object with thinking
        ThinkingResponse<String> response = runner
            .thinking()
            .createObject(prompt, String.class);

        // Then: Verify both result and thinking content
        assertNotNull(response, "Response should not be null");

        logger.info("Guards violations: {}", response);
    }
}
