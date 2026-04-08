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
package com.embabel.agent.config.models.gemini;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail;
import com.embabel.agent.autoconfigure.models.gemini.AgentGeminiAutoConfiguration;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.core.thinking.ThinkingResponse;
import com.embabel.common.core.validation.ValidationResult;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Gemini integration tests for GuardRails functionality with structured output.
 * Tests that AssistantMessageGuardRail is correctly invoked/skipped for structured object responses.
 */
@SpringBootTest(
        properties = {
                "embabel.models.default-llm=gemini-2.5-flash",
                "embabel.agent.platform.models.gemini.max-attempts=1",
                "embabel.agent.platform.llm-operations.prompts.defaultTimeout=240s",
                "spring.main.allow-bean-definition-overriding=true",
                "logging.level.com.embabel.agent.spi.support.springai.ChatClientLlmOperations=TRACE",
        }
)
@ActiveProfiles("gemini-chat-test")
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
@Import({AgentGeminiAutoConfiguration.class})
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+", disabledReason = "Integration test requires GEMINI_API_KEY")
class LLMGeminiGuardRailsIntegrationIT {

    private static final Logger logger = LoggerFactory.getLogger(LLMGeminiGuardRailsIntegrationIT.class);

    @Autowired
    private Ai ai;

    @Autowired
    private List<LlmService<?>> llms;

    /**
     * Simple data class for testing structured object creation
     */
    static class MonthItem {
        private String name;
        private Integer temperature;

        public MonthItem() {
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
     * Tests that AssistantMessageGuardRail is invoked for non-thinking structured output (createObject).
     */
    @Test
    void testGuardRailInvokedForStructuredCreateObject() {
        logger.info("Starting guardrail structured createObject test");

        List<String> guardRailCalled = Collections.synchronizedList(new ArrayList<>());

        AssistantMessageGuardRail trackingGuard = new AssistantMessageGuardRail() {
            @Override
            public @NotNull String getName() {
                return "StructuredOutputTrackingGuardRail";
            }

            @Override
            public @NotNull String getDescription() {
                return "Tracks guardrail invocation for structured output";
            }

            @Override
            public @NotNull ValidationResult validate(@NotNull String input, @NotNull Blackboard blackboard) {
                guardRailCalled.add(input);
                logger.info("AssistantMessageGuardRail invoked for structured output: {}", input);
                return new ValidationResult(true, Collections.emptyList());
            }

            @Override
            public @NotNull ValidationResult validate(@NotNull ThinkingResponse<?> response, @NotNull Blackboard blackboard) {
                return new ValidationResult(true, Collections.emptyList());
            }
        };

        PromptRunner runner = ai.withLlm("gemini-2.5-flash")
                .withGuardRails(trackingGuard);

        String prompt = "What is the hottest month in Florida and provide its temperature in Fahrenheit.";

        MonthItem result = runner.createObject(prompt, MonthItem.class);

        assertNotNull(result, "Result should not be null");
        assertNotNull(result.getName(), "Month name should not be null");
        assertFalse(guardRailCalled.isEmpty(),
                "AssistantMessageGuardRail should have been called for structured output");
        logger.info("GuardRail was invoked {} time(s) for structured createObject", guardRailCalled.size());
    }

    /**
     * Tests that AssistantMessageGuardRail is invoked for non-thinking structured output (createObjectIfPossible).
     */
    @Test
    void testGuardRailInvokedForStructuredCreateObjectIfPossible() {
        logger.info("Starting guardrail structured createObjectIfPossible test");

        List<String> guardRailCalled = Collections.synchronizedList(new ArrayList<>());

        AssistantMessageGuardRail trackingGuard = new AssistantMessageGuardRail() {
            @Override
            public @NotNull String getName() {
                return "StructuredOutputTrackingGuardRail";
            }

            @Override
            public @NotNull String getDescription() {
                return "Tracks guardrail invocation for structured output";
            }

            @Override
            public @NotNull ValidationResult validate(@NotNull String input, @NotNull Blackboard blackboard) {
                guardRailCalled.add(input);
                logger.info("AssistantMessageGuardRail invoked for structured output: {}", input);
                return new ValidationResult(true, Collections.emptyList());
            }

            @Override
            public @NotNull ValidationResult validate(@NotNull ThinkingResponse<?> response, @NotNull Blackboard blackboard) {
                return new ValidationResult(true, Collections.emptyList());
            }
        };

        PromptRunner runner = ai.withLlm("gemini-2.5-flash")
                .withGuardRails(trackingGuard);

        String prompt = "January has an average temperature of 50 degrees Fahrenheit. Extract the month name and temperature.";

        MonthItem result = runner.createObjectIfPossible(prompt, MonthItem.class);

        assertNotNull(result, "Result should not be null");
        assertNotNull(result.getName(), "Month name should not be null");
        assertFalse(guardRailCalled.isEmpty(),
                "AssistantMessageGuardRail should have been called for structured output");
        logger.info("GuardRail was invoked {} time(s) for structured createObjectIfPossible", guardRailCalled.size());
    }

}
