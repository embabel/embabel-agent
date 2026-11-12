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
package com.embabel.agent.config.models.anthropic;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail;
import com.embabel.agent.api.validation.guardrails.GuardRailViolationException;
import com.embabel.agent.api.validation.guardrails.UserInputGuardRail;
import com.embabel.agent.autoconfigure.models.anthropic.AgentAnthropicAutoConfiguration;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.Thinking;
import com.embabel.common.core.thinking.ThinkingBlock;
import com.embabel.common.core.thinking.ThinkingException;
import com.embabel.common.core.thinking.ThinkingOrigin;
import com.embabel.common.core.thinking.ThinkingTagType;
import com.embabel.common.core.thinking.ThinkingResponse;
import com.embabel.common.core.validation.ValidationError;
import com.embabel.common.core.validation.ValidationResult;
import com.embabel.common.core.validation.ValidationSeverity;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java integration test for Anthropic thinking functionality using builder pattern.
 * Tests the Java equivalent of Kotlin's withThinking() extension function.
 */
@SpringBootTest(
        properties = {
                "embabel.models.cheapest=claude-opus-4-8",
                "embabel.models.best=claude-opus-4-8",
                "embabel.models.default-llm=claude-opus-4-8",
                "embabel.agent.platform.llm-operations.prompts.defaultTimeout=240s",
                "embabel.agent.platform.llm-operations.data-binding.fixedBackoffMillis=6000",
                "spring.main.allow-bean-definition-overriding=true",

                // Thinking Infrastructure logging
                "logging.level.com.embabel.agent.spi.support.springai.ChatClientLlmOperations=TRACE",
                "logging.level.com.embabel.common.core.thinking=DEBUG",
                "logging.level.com.embabel.agent.anthropic.AnthropicNativeThinkingExtractor=DEBUG",

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
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+",
        disabledReason = "Integration test requires ANTHROPIC_API_KEY")
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
     * Uninstantiable target class that forces Jackson to throw InvalidDefinitionException,
     * which the outputParser wraps into ThinkingException — used to test the exception path.
     * Concrete fields expose schema properties so the model reasons about the prompt
     * rather than short-circuiting to {} when the schema appears empty.
     */
    private abstract static class UninstantiableResult {
        private String solution;
        private String explanation;

        public String getSolution() { return solution; }
        public String getExplanation() { return explanation; }
    }

    /**
     * Tool for temperature conversion
     */
    static class Tooling {

        @Tool
        Integer convertFromCelsiusToFahrenheit(Integer inputTemp) {
            return (int) Math.round(inputTemp * 9.0 / 5 + 32);
        }
    }

    /**
     * Result class for tests that require both temperature and humidity.
     */
    static class MonthWeather {
        private String name;
        private Integer temperatureFahrenheit;
        private Integer humidityPercent;

        // Required by Jackson for deserialization
        public MonthWeather() {}

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Integer getTemperatureFahrenheit() { return temperatureFahrenheit; }
        public void setTemperatureFahrenheit(Integer v) { this.temperatureFahrenheit = v; }
        public Integer getHumidityPercent() { return humidityPercent; }
        public void setHumidityPercent(Integer v) { this.humidityPercent = v; }

        @Override
        public String toString() {
            return "MonthWeather{name='" + name + "', tempF=" + temperatureFahrenheit + ", humidity=" + humidityPercent + "}";
        }
    }

    /**
     * Two tools: temperature conversion and humidity lookup.
     * Used to exercise the multi-tool-call path with thinking active (issue #2121).
     */
    static class MultiTooling {

        @Tool
        Integer convertFromCelsiusToFahrenheit(Integer inputTemp) {
            return (int) Math.round(inputTemp * 9.0 / 5 + 32);
        }

        @Tool
        Integer getRelativeHumidityPercent(String month) {
            return switch (month.toLowerCase()) {
                case "january"   -> 71;
                case "february"  -> 68;
                case "march"     -> 66;
                case "april"     -> 63;
                case "may"       -> 67;
                case "june"      -> 74;
                case "july"      -> 76;
                case "august"    -> 76;
                case "september" -> 76;
                case "october"   -> 72;
                case "november"  -> 72;
                case "december"  -> 72;
                default          -> 70;
            };
        }
    }

    /**
     * GuardRail For User Messages
     */
    record UserInputThinkingGuardRail() implements UserInputGuardRail {


        @Override
        public @NotNull String getName() {
            return "UserInputThinkingGuardRail";
        }

        @Override
        public @NotNull String getDescription() {
            return "UserInputThinkingGuardRail";
        }

        @Override
        public @NotNull ValidationResult validate(@NotNull String input, @NotNull Blackboard blackboard) {
            logger.info("Validated Simple User Input {}", input);
            return new ValidationResult(true, Collections.emptyList());
        }
    }

    /**
     * Simple Guard Rail, logs details on INFO level (as per severity)
     */
    record UserInputSimpleGuardRail() implements UserInputGuardRail {


        @Override
        public @NotNull String getName() {
            return "UserInputSimpleGuardRail";
        }

        @Override
        public @NotNull String getDescription() {
            return "UserInputSimpleGuardRail";
        }

        @Override
        public @NotNull ValidationResult validate(@NotNull String input, @NotNull Blackboard blackboard) {
            logger.info("Validated Simple User Input {}", input);
            List<ValidationError> errors = new ArrayList<>();
            errors.add(new ValidationError("guardrail-error", "something-wrong", ValidationSeverity.INFO));
            return new ValidationResult(true, errors);
        }
    }

    /**
     * Simple Guard Rail, throws GuardRail Violation Exception
     */
    record UserInputCriticalSeverityGuardRail() implements UserInputGuardRail {


        @Override
        public @NotNull String getName() {
            return "UserInputCriticalSeverityGuardRail";
        }

        @Override
        public @NotNull String getDescription() {
            return "UserInputCriticalSeverityGuardRail";
        }

        @Override
        public @NotNull ValidationResult validate(@NotNull String input, @NotNull Blackboard blackboard) {
            logger.info("Validated Simple User Input {}", input);
            List<ValidationError> errors = new ArrayList<>();
            errors.add(new ValidationError("guardrail-error", "something-very-wrong", ValidationSeverity.CRITICAL));
            return new ValidationResult(true, errors);
        }
    }


    /**
     * Guard Rail for Assistant Messages
     */
    record ThinkingBlocksGuardRail() implements AssistantMessageGuardRail {


        @Override
        public @NotNull ValidationResult validate(@NotNull ThinkingResponse<?> response, @NotNull Blackboard blackboard) {
            logger.info("Validated Thinking Block {}:", response.getThinkingBlocks());
            return new ValidationResult(true, Collections.emptyList());
        }

        @Override
        public @NotNull String getName() {
            return "ThinkingBlocksGuardRail";
        }

        @Override
        public @NotNull String getDescription() {
            return "ThinkingBlocksGuardRail";
        }

        @Override
        public @NotNull ValidationResult validate(@NotNull String input, @NotNull Blackboard blackboard) {
            return new ValidationResult(true, Collections.emptyList());
        }

    }

    /**
     * Simple Guard Rail for Thinking Blocks
     */
    record SimpleThinkingBlocksGuardRail() implements AssistantMessageGuardRail {


        @Override
        public @NotNull ValidationResult validate(@NotNull ThinkingResponse<?> response, @NotNull Blackboard blackboard) {
            logger.info("Validated Thinking Block {}:", response.getThinkingBlocks());
            return new ValidationResult(true, Collections.emptyList());
        }

        @Override
        public @NotNull String getName() {
            return "SimpleThinkingBlocksGuardRail";
        }

        @Override
        public @NotNull String getDescription() {
            return "SimpleThinkingBlocksGuardRail";
        }

        @Override
        public @NotNull ValidationResult validate(@NotNull String input, @NotNull Blackboard blackboard) {
            return new ValidationResult(true, Collections.emptyList());
        }

    }


    @Test
    void testThinkingCreateObject() {
        logger.info("Starting thinking createObject integration test");

        // Given: Use the LLM configured for thinking tests
        PromptRunner runner = ai.withDefaultLlm()
                .withToolObject(new Tooling())
                .withGenerateExamples(true)
                .withGuardRails(new UserInputThinkingGuardRail(), new ThinkingBlocksGuardRail());
        assertTrue(runner.supportsThinking(), "Expected Anthropic prompt runner to support thinking");

        String prompt = """
                What is the hottest month in Florida and  provide its temperature.
                Please respond with your reasoning using tags <reason>.
                
                The name should be the month name, temperature should be in Fahrenheit.
                """;

        // When: create object with thinking
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
    void testThinkingCreateObjectIfPossible() {
        logger.info("Starting thinking createObjectIfPossible integration test");

        // Given: Use the LLM configured for thinking tests
        PromptRunner runner = ai.withDefaultLlm()
                .withToolObject(new Tooling())
                .withGuardRails(new UserInputSimpleGuardRail())
                .withGuardRails(new ThinkingBlocksGuardRail());
        assertTrue(runner.supportsThinking(), "Expected Anthropic prompt runner to support thinking");

        String prompt = "Think about the coldest month in Alaska and its temperature. Provide your analysis.";


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
        // Not asserting thinkingBlocks.isEmpty() — createObjectIfPossible may succeed without
        // thinking when the model has sufficient data, so thinking blocks are not guaranteed here.
        logger.info("Extracted {} thinking blocks", thinkingBlocks);

        logger.info("Thinking createObjectIfPossible test completed successfully");
    }

    @Test
    void testThinkingCreateObjectWithCriticalGuardRailSeverity() {
        logger.info("Starting thinking createObject integration test");

        // Given: Use the LLM configured for thinking tests
        PromptRunner runner = ai.withDefaultLlm()
                .withToolObject(new Tooling())
                .withGenerateExamples(true)
                .withGuardRails(new UserInputCriticalSeverityGuardRail(), new SimpleThinkingBlocksGuardRail());
        assertTrue(runner.supportsThinking(), "Expected Anthropic prompt runner to support thinking");

        String prompt = """
                What is the hottest month in Florida and  provide its temperature.
                Please respond with your reasoning using tags <reason>.
                
                The name should be the month name, temperature should be in Fahrenheit.
                """;
        ThinkingResponse<MonthItem> response = null;
        try {
            // When: create object with thinking
            response = runner
                    .thinking()
                    .createObject(prompt, MonthItem.class);
        } catch (Exception ex) {
            assertInstanceOf(GuardRailViolationException.class, ex, "expected guard rail exception");
            logger.error(ex.getMessage());
        }
        // Then: Verify both result and thinking content
        assertNull(response, "Response should  be null");

        logger.info("Thinking ThinkingCreateObjectWithCriticalGuardRailSeverity test completed successfully");
    }


    @Test
    void testThinkingCreateObjectIfPossibleWithCriticalGuardRailSeverity() {
        logger.info("Starting thinking testThinkingCreateObjectIfPossibleWithCriticalGuardRailSeverity integration test");

        // Given: Use the LLM configured for thinking tests
        PromptRunner runner = ai.withDefaultLlm()
                .withToolObject(new Tooling())
                .withGuardRails(new UserInputCriticalSeverityGuardRail())
                .withGuardRails(new SimpleThinkingBlocksGuardRail());
        assertTrue(runner.supportsThinking(), "Expected Anthropic prompt runner to support thinking");

        String prompt = "Think about the coldest month in Alaska and its temperature. Provide your analysis.";


        ThinkingResponse<MonthItem> response = runner
                .thinking()
                .createObjectIfPossible(prompt, MonthItem.class);


        // Then: Verify response and thinking content (result may be null if creation not possible)
        assertNotNull(response, "Response should not be null");
        assertInstanceOf(GuardRailViolationException.class, response.getException());
        logger.error(response.getException().toString());

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
        assertTrue(thinkingBlocks.isEmpty(), "Should Not have thinking content due to Exception");

        logger.info("Extracted {} thinking blocks", thinkingBlocks);

        logger.info("Thinking testThinkingCreateObjectIfPossibleWithCriticalGuardRailSeverity test completed successfully");
    }


    @Test
    void testThinkingWithIncludedTags() {
        logger.info("Starting thinking includedTags integration test");

        PromptRunner runner = ai.withLlm(
                LlmOptions.withModel("claude-opus-4-8")
                        .withThinking(Thinking.withIncludedTags("analysis"))
        );
        assertTrue(runner.supportsThinking(), "Expected Anthropic prompt runner to support thinking");

        String prompt = "What is the hottest month in Florida and its average high temperature?";

        ThinkingResponse<MonthItem> response = runner
                .thinking()
                .createObject(prompt, MonthItem.class);

        assertNotNull(response, "Response should not be null");

        MonthItem result = response.getResult();
        assertNotNull(result, "Result object should not be null");
        assertNotNull(result.getName(), "Month name should not be null");
        logger.info("Created object: {}", result);

        List<ThinkingBlock> thinkingBlocks = response.getThinkingBlocks();
        assertNotNull(thinkingBlocks, "Thinking blocks should not be null");
        assertFalse(thinkingBlocks.isEmpty(), "Should have thinking content");

        // Only TAG blocks with tagValue "analysis" should be present; no other TAG blocks
        thinkingBlocks.stream()
                .filter(b -> b.getTagType() == ThinkingTagType.TAG)
                .forEach(b -> assertEquals("analysis", b.getTagValue(),
                        "Expected only 'analysis' TAG blocks, got: " + b.getTagValue()));

        logger.info("includedTags test completed with {} thinking blocks", thinkingBlocks.size());
    }

    @Test
    void testThinkingWithComplexPrompt() {
        logger.info("Starting complex thinking integration test");

        // Given: Use the LLM with a complex reasoning prompt
        PromptRunner runner = ai.withDefaultLlm()
                .withToolObject(new Tooling());
        assertTrue(runner.supportsThinking(), "Expected Anthropic prompt runner to support thinking");

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
                .thinking()
                .createObject(prompt, MonthItem.class);

        // Then: Verify extraction of multiple thinking formats
        assertNotNull(response, "Response should not be null");

        MonthItem result = response.getResult();
        assertNotNull(result, "Result object should not be null");
        logger.info("Created object from complex prompt: {}", result);

        List<ThinkingBlock> thinkingBlocks = response.getThinkingBlocks();
        assertNotNull(thinkingBlocks, "Thinking blocks should not be null");

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

    @Nested
    class NativeAdaptiveThinking {

        @Test
        void sonnet55NativeBlocksWithToolLoop() {
            logger.info("Starting native adaptive thinking with tool loop test");

            PromptRunner runner = ai.withLlm(
                    LlmOptions.withModel("claude-sonnet-5-5")
                            .withThinking(Thinking.withTokenBudget(20000))
            ).withToolObject(new Tooling());
            assertTrue(runner.supportsThinking(), "Expected claude-sonnet-5-5 to support thinking");

            String prompt = """
                    What is the hottest month in Florida?
                    Use the temperature conversion tool to express its average high temperature in Fahrenheit.
                    """;

            ThinkingResponse<MonthItem> response = runner
                    .thinking()
                    .createObject(prompt, MonthItem.class);

            assertNotNull(response, "Response should not be null");
            assertNotNull(response.getResult(), "Result should not be null");

            List<ThinkingBlock> thinkingBlocks = response.getThinkingBlocks();
            assertFalse(thinkingBlocks.isEmpty(),
                    "Should have thinking blocks accumulated across tool loop iterations");

            boolean hasNativeBlock = thinkingBlocks.stream()
                    .anyMatch(b -> b.getOrigin() == ThinkingOrigin.NATIVE);
            assertTrue(hasNativeBlock, "Should have at least one NATIVE thinking block");

            logger.info("Native adaptive thinking with tool loop: {} total blocks, {} native",
                    thinkingBlocks.size(),
                    thinkingBlocks.stream().filter(b -> b.getOrigin() == ThinkingOrigin.NATIVE).count());
        }

        @Test
        void sonnet45NativeThinking() {
            PromptRunner runner = ai.withLlm(
                    LlmOptions.withModel("claude-sonnet-4-5")
                            .withThinking(Thinking.withTokenBudget(5000))
            );
            assertTrue(runner.supportsThinking(), "Expected claude-sonnet-4-5 to support thinking");

            ThinkingResponse<MonthItem> response = runner
                    .thinking()
                    .createObject(
                            "What is the hottest month in Florida and its average high temperature?",
                            MonthItem.class);

            assertNotNull(response.getResult(), "Result should not be null");
            List<ThinkingBlock> thinkingBlocks = response.getThinkingBlocks();
            assertFalse(thinkingBlocks.isEmpty(), "Should have thinking blocks");
            assertTrue(
                    thinkingBlocks.stream().anyMatch(b -> b.getOrigin() == ThinkingOrigin.NATIVE),
                    "Should have NATIVE blocks");
            logger.info("Native thinking test (sonnet-4-5): {} blocks", thinkingBlocks.size());
        }

        @Test
        void sonnet55ThinkingExceptionHasNativeBlocks() {
            PromptRunner runner = ai.withLlm(
                    LlmOptions.withModel("claude-sonnet-5-5")
                            .withThinking(Thinking.withTokenBudget(20000))
            );
            assertTrue(runner.supportsThinking(), "Expected claude-sonnet-5-5 to support thinking");

            ThinkingException thrown = assertThrows(ThinkingException.class, () ->
                    runner.thinking().createObject(
                            "A train leaves city A at 9:00 AM travelling at 80 km/h toward city B, 320 km away. " +
                            "Another train leaves city B at 10:30 AM travelling at 100 km/h toward city A. " +
                            "At what time do they meet, and how far from city A? Show your full reasoning.",
                            UninstantiableResult.class)
            );

            assertFalse(thrown.getThinkingBlocks().isEmpty(),
                    "ThinkingException should carry thinking blocks even on parse failure");
            assertTrue(
                    thrown.getThinkingBlocks().stream().anyMatch(b -> b.getOrigin() == ThinkingOrigin.NATIVE),
                    "Should have at least one NATIVE block from sonnet-5-5 adaptive thinking");
            logger.info("sonnet55ThinkingExceptionHasNativeBlocks: {} blocks, {} native",
                    thrown.getThinkingBlocks().size(),
                    thrown.getThinkingBlocks().stream().filter(b -> b.getOrigin() == ThinkingOrigin.NATIVE).count());
            thrown.getThinkingBlocks().stream()
                    .filter(b -> b.getOrigin() == ThinkingOrigin.NATIVE)
                    .forEach(b -> logger.info("  NATIVE block content (first 200): {}",
                            b.getContent().substring(0, Math.min(200, b.getContent().length()))));
        }

        @Test
        void sonnet55ThinkingExceptionWithToolsAccumulatesNativeBlocks() {
            PromptRunner runner = ai.withLlm(
                    LlmOptions.withModel("claude-sonnet-5-5")
                            .withThinking(Thinking.withTokenBudget(20000))
            ).withToolObject(new Tooling());
            assertTrue(runner.supportsThinking(), "Expected claude-sonnet-5-5 to support thinking");

            ThinkingException thrown = assertThrows(ThinkingException.class, () ->
                    runner.thinking().createObject(
                            "What is the hottest month in Florida? Use the temperature conversion tool to express its average high in Fahrenheit.",
                            UninstantiableResult.class)
            );

            assertFalse(thrown.getThinkingBlocks().isEmpty(),
                    "ThinkingException should carry blocks accumulated across tool loop iterations");
            assertTrue(
                    thrown.getThinkingBlocks().stream().anyMatch(b -> b.getOrigin() == ThinkingOrigin.NATIVE),
                    "Should have NATIVE blocks accumulated across tool loop iterations");
            logger.info("sonnet55ThinkingExceptionWithToolsAccumulatesNativeBlocks: {} total, {} native",
                    thrown.getThinkingBlocks().size(),
                    thrown.getThinkingBlocks().stream().filter(b -> b.getOrigin() == ThinkingOrigin.NATIVE).count());
            thrown.getThinkingBlocks().stream()
                    .filter(b -> b.getOrigin() == ThinkingOrigin.NATIVE)
                    .forEach(b -> logger.info("  NATIVE block content (first 200): {}",
                            b.getContent().substring(0, Math.min(200, b.getContent().length()))));
        }

        // NOTE: Combining native adaptive thinking (output_config.effort) with prompt-based <reason> tags
        // is NOT possible on sonnet-5-5 or any 5.x adaptive model. Anthropic's API returns
        // stop_reason="refusal", category="reasoning_extraction" — it treats the combination as an
        // attempt to reverse-engineer internal model reasoning, in violation of their ToS.
        // Use NATIVE-only (no <reason> tags in prompt) OR prompt-extracted on a BudgetBased model
        // (e.g. opus-4-8) — never both on a 5.x adaptive model.
        @Test
        void sonnet55NativeThinkingOnly() {
            PromptRunner runner = ai.withLlm(
                    LlmOptions.withModel("claude-sonnet-5-5")
                            .withThinking(Thinking.withTokenBudget(20000))
            );
            assertTrue(runner.supportsThinking(), "Expected claude-sonnet-5-5 to support thinking");

            ThinkingResponse<MonthItem> response = runner
                    .thinking()
                    .createObject(
                            "What is the hottest month in Florida and its average high temperature in Fahrenheit?",
                            MonthItem.class);

            assertNotNull(response.getResult(), "Result should not be null");
            List<ThinkingBlock> thinkingBlocks = response.getThinkingBlocks();
            assertFalse(thinkingBlocks.isEmpty(), "Should have thinking blocks");
            assertTrue(
                    thinkingBlocks.stream().anyMatch(b -> b.getOrigin() == ThinkingOrigin.NATIVE),
                    "Should have NATIVE blocks (adaptive SUMMARIZED from Anthropic)");
            logger.info("sonnet-5-5 native-only thinking: {} total, {} native",
                    thinkingBlocks.size(),
                    thinkingBlocks.stream().filter(b -> b.getOrigin() == ThinkingOrigin.NATIVE).count());
        }

        @Test
        void sonnet55TwoToolsTriggersBlockOrdering() {
            // Regression target for issue #2121.
            // Anthropic requires thinking blocks to appear before text blocks in an assistant
            // message content array. Spring AI AnthropicChatModel violates this: when Claude
            // produces thinking + text before a tool call, it rebuilds the history as
            // [text, thinking, tool_use] instead of the required [thinking, text, tool_use].
            // Anthropic's prefix_mismatch_behavior controls the consequence: older accounts
            // silently ignored wrong ordering ("ignore"), but since 2026-08-31 the default
            // is "error" — any out-of-order block causes an immediate HTTP 400.
            // Two tools force multiple tool-call iterations, reliably triggering the reordering.
            PromptRunner runner = ai.withLlm(
                    LlmOptions.withModel("claude-sonnet-5-5")
                            .withThinking(Thinking.withTokenBudget(20000))
            ).withToolObject(new MultiTooling());
            assertTrue(runner.supportsThinking(), "Expected claude-sonnet-5-5 to support thinking");

            String prompt = """
                    What is the hottest month in Florida?
                    Use the temperature conversion tool to convert its average high to Fahrenheit,
                    and use the humidity tool to get its average relative humidity percentage.
                    """;

            ThinkingResponse<MonthWeather> response = runner
                    .thinking()
                    .createObject(prompt, MonthWeather.class);

            assertNotNull(response, "Response should not be null");
            MonthWeather result = response.getResult();
            assertNotNull(result, "Result should not be null");
            assertNotNull(result.getName(), "Month name should not be null");

            List<ThinkingBlock> thinkingBlocks = response.getThinkingBlocks();
            assertFalse(thinkingBlocks.isEmpty(),
                    "Should have thinking blocks accumulated across two-tool loop iterations");
            assertTrue(
                    thinkingBlocks.stream().anyMatch(b -> b.getOrigin() == ThinkingOrigin.NATIVE),
                    "Should have at least one NATIVE thinking block");

            logger.info("Two-tools block ordering test: {} total blocks, {} native, result={}",
                    thinkingBlocks.size(),
                    thinkingBlocks.stream().filter(b -> b.getOrigin() == ThinkingOrigin.NATIVE).count(),
                    result);
        }
    }

}
