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
package com.embabel.agent.config.models.anthropic;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.autoconfigure.models.anthropic.AgentAnthropicAutoConfiguration;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.core.thinking.ThinkingBlock;
import com.embabel.common.core.thinking.ThinkingResponse;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Java integration test for Ollama thinking functionality using builder pattern.
 * Tests the Java equivalent of Kotlin's withThinking() extension function.
 */
@SpringBootTest(
        properties = {
                "embabel.models.cheapest=claude_-sonnet-4-5",
                "embabel.models.best=claude_-sonnet-4-5",
                "embabel.models.default-llm=claude-sonnet-4-5",
                "embabel.agent.platform.llm-operations.prompts.defaultTimeout=240",
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
@Import({AgentAnthropicAutoConfiguration.class})
class LLMAnthropicThinkingIT {

    private static final Logger logger = LoggerFactory.getLogger(LLMAnthropicThinkingIT.class);

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

        private Integer temperature;

        public MonthItem() {
        }

        public MonthItem(String name) {
            this.name = name;
        }

        public MonthItem(String name, Integer temperature) {
            this.name = name;
            this.temperature = temperature;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getTemperature() {
            return temperature;
        }

        public void setTemperature(Integer temperature) {
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
        Integer convertFromCelsiusToFahrenheit(Integer inputTemp) {
            return (int) ((inputTemp * 2) + 32);
        }
    }

    @Test
    void testThinkingCreateObject() {
        logger.info("Starting thinking createObject integration test");

        // Given: Use the LLM configured for thinking tests
        PromptRunner runner = ai.withLlm("claude-sonnet-4-5")
                .withToolObject(Tooling.class)
                .withGenerateExamples(true);

        String prompt = """
                What is the hottest month in Florida and  provide its temperature.
                Please respond with your reasoning using tags <reason>.
                
                The name should be the month name, temperature should be in Fahrenheit.
                """;

        // When: create object with thinking
        ThinkingResponse<MonthItem> response = runner
                .withThinking()
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
    void testThinkingCreateObjectIfPossible() {
        logger.info("Starting thinking createObjectIfPossible integration test");

        // Given: Use the LLM configured for thinking tests
        PromptRunner runner = ai.withLlm("claude-sonnet-4-5")
                .withToolObject(Tooling.class);


        String prompt = "Think about the coldest month in Alaska and its temperature. Provide your analysis.";

        // When: Use factory method for more natural chaining - not recommended (testing alternative syntax)
        ThinkingResponse<MonthItem> response = runner
                .withThinking()
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
        PromptRunner runner = ai.withLlm("claude-sonnet-4-5")
                                .withToolObject(Tooling.class);

        String prompt = """
                <think>
                I need to carefully analyze seasonal patterns and temperature data.
                Let me think step by step about Florida's climate.
                </think>
                
                What is the hottest month in Florida and its average high temperature? 
                Please provide a detailed analysis of your reasoning.
                
                //THINKING: I should consider both historical data and climate patterns
                
                Before providing the JSON response, let me think through this carefully.
                """;


        ThinkingResponse<MonthItem> response = runner
                .withThinking()
                .createObject(prompt, MonthItem.class);

        // Then: Verify extraction of multiple thinking formats
        assertNotNull(response, "Response should not be null");

        MonthItem result = response.getResult();
        assertNotNull(result, "Result object should not be null");
        logger.info("Created object from complex prompt: {}", result);

        List<ThinkingBlock> thinkingBlocks = response.getThinkingBlocks();
        assertNotNull(thinkingBlocks, "Thinking blocks should not be null");
        assertFalse(thinkingBlocks.isEmpty(), "Should extract multiple thinking formats");

        // Verify we extracted different types of thinking content
        boolean hasTagThinking = thinkingBlocks.stream()
                .anyMatch(block -> block.getTagType().name().equals("TAG"));
        boolean hasPrefixThinking = thinkingBlocks.stream()
                .anyMatch(block -> block.getTagType().name().equals("PREFIX"));
        boolean hasNoPrefixThinking = thinkingBlocks.stream()
                .anyMatch(block -> block.getTagType().name().equals("NO_PREFIX"));

        logger.info("Thinking formats detected - TAG: {}, PREFIX: {}, NO_PREFIX: {}",
                hasTagThinking, hasPrefixThinking, hasNoPrefixThinking);

        logger.info("Complex thinking test completed successfully with {} thinking blocks",
                thinkingBlocks.size());
    }
}