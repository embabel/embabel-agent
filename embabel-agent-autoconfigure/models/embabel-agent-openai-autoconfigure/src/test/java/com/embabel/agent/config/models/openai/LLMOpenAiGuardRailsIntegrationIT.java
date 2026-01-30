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
package com.embabel.agent.config.models.openai;


import com.embabel.agent.api.common.Ai;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail;
import com.embabel.agent.api.validation.guardrails.GuardRailViolationException;
import com.embabel.agent.api.validation.guardrails.UserInputGuardRail;
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.core.thinking.ThinkingBlock;
import com.embabel.common.core.thinking.ThinkingResponse;
import com.embabel.common.core.validation.ValidationError;
import com.embabel.common.core.validation.ValidationResult;
import com.embabel.common.core.validation.ValidationSeverity;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.moderations.ModerationCreateParams;
import com.openai.models.moderations.ModerationCreateResponse;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.moderation.Moderation;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.openai.OpenAiModerationModel;
import org.springframework.ai.openai.api.OpenAiModerationApi;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wrapper around OpenAI SDK client for moderation API.
 */
class OpenAiModerationClient {

    private final OpenAIClient client;

    public OpenAiModerationClient() {
        this.client = OpenAIOkHttpClient.fromEnv(); // Automatically uses OPENAI_API_KEY
    }

    public ModerationCreateResponse moderate(String inputText) {
        ModerationCreateParams params = ModerationCreateParams.builder()
                .input(inputText)
                .model("omni-moderation-latest")
                .build();

        return client.moderations().create(params);
    }
}

/**
 * GuardRail implementation using OpenAI Moderation API.
 */
class OpenAiGuardRail implements UserInputGuardRail {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiGuardRail.class);
    private final OpenAiModerationClient client;

    public OpenAiGuardRail(OpenAiModerationClient client) {
        this.client = client;
    }

    @Override
    public @NotNull String getName() {
        return "OpenAiGuardRail";
    }

    @Override
    public @NotNull String getDescription() {
        return "Validates user input using OpenAI Moderation API";
    }

    @Override
    public @NotNull ValidationResult validate(@NotNull String inputText, @NotNull Blackboard blackboard) {
        logger.info("Validating input with OpenAI Moderation API: {}", inputText);

        ModerationCreateResponse response = client.moderate(inputText);

        var result = response.results().get(0);
        boolean flagged = result.flagged();
        logger.info("Content Flagged: {}", flagged);

        if (flagged) {
            // Extract flagged categories
            var categories = result.categories();
            List<String> flaggedCategories = new ArrayList<>();

            if (categories.hate()) flaggedCategories.add("hate");
            if (categories.hateThreatening()) flaggedCategories.add("hate/threatening");
            if (categories.harassment()) flaggedCategories.add("harassment");
            if (categories.harassmentThreatening()) flaggedCategories.add("harassment/threatening");
            if (categories.selfHarm()) flaggedCategories.add("self-harm");
            if (categories.selfHarmIntent()) flaggedCategories.add("self-harm/intent");
            if (categories.selfHarmInstructions()) flaggedCategories.add("self-harm/instructions");
            if (categories.sexual()) flaggedCategories.add("sexual");
            if (categories.sexualMinors()) flaggedCategories.add("sexual/minors");
            if (categories.violence()) flaggedCategories.add("violence");
            if (categories.violenceGraphic()) flaggedCategories.add("violence/graphic");

            String categoriesStr = String.join(", ", flaggedCategories);
            logger.info("Flagged categories: {}", categoriesStr);

            List<ValidationError> errors = new ArrayList<>();
            errors.add(new ValidationError(
                    "openai-moderation-flagged",
                    "Content flagged by OpenAI Moderation API. Categories: " + categoriesStr,
                    ValidationSeverity.CRITICAL
            ));
            return new ValidationResult(false, errors);
        }

        return new ValidationResult(true, Collections.emptyList());
    }
}

/**
 * Wrapper around Spring AI OpenAiModerationModel for moderation API.
 */
class SpringAiModerationClient {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiModerationClient.class);
    private final OpenAiModerationModel moderationModel;

    public SpringAiModerationClient(OpenAiModerationModel moderationModel) {
        this.moderationModel = moderationModel;
    }

    public Moderation moderate(String inputText) {
        logger.debug("Calling Spring AI Moderation API with input: {}", inputText);
        ModerationPrompt prompt = new ModerationPrompt(inputText);
        ModerationResponse response = moderationModel.call(prompt);
        return response.getResult().getOutput();
    }
}

/**
 * GuardRail implementation using Spring AI Moderation API.
 */
class SpringAiGuardRail implements UserInputGuardRail {

    private static final Logger logger = LoggerFactory.getLogger(SpringAiGuardRail.class);
    private final SpringAiModerationClient client;

    public SpringAiGuardRail(SpringAiModerationClient client) {
        this.client = client;
    }

    @Override
    public @NotNull String getName() {
        return "SpringAiGuardRail";
    }

    @Override
    public @NotNull String getDescription() {
        return "Validates user input using Spring AI Moderation API";
    }

    @Override
    public @NotNull ValidationResult validate(@NotNull String inputText, @NotNull Blackboard blackboard) {
        logger.info("Validating input with Spring AI Moderation API: {}", inputText);

        Moderation moderation = client.moderate(inputText);
        var results = moderation.getResults();

        if (results.isEmpty()) {
            return new ValidationResult(true, Collections.emptyList());
        }

        var result = results.get(0);
        boolean flagged = result.isFlagged();
        logger.info("Content Flagged: {}", flagged);

        if (flagged) {
            // Extract flagged categories
            var categories = result.getCategories();
            List<String> flaggedCategories = new ArrayList<>();

            if (categories.isHate()) flaggedCategories.add("hate");
            if (categories.isHateThreatening()) flaggedCategories.add("hate/threatening");
            if (categories.isHarassment()) flaggedCategories.add("harassment");
            if (categories.isHarassmentThreatening()) flaggedCategories.add("harassment/threatening");
            if (categories.isSelfHarm()) flaggedCategories.add("self-harm");
            if (categories.isSelfHarmIntent()) flaggedCategories.add("self-harm/intent");
            if (categories.isSelfHarmInstructions()) flaggedCategories.add("self-harm/instructions");
            if (categories.isSexual()) flaggedCategories.add("sexual");
            if (categories.isSexualMinors()) flaggedCategories.add("sexual/minors");
            if (categories.isViolence()) flaggedCategories.add("violence");
            if (categories.isViolenceGraphic()) flaggedCategories.add("violence/graphic");
            if (categories.isPii()) flaggedCategories.add("PII Violation");

            String categoriesStr = String.join(", ", flaggedCategories);
            logger.info("Flagged categories: {}", categoriesStr);

            List<ValidationError> errors = new ArrayList<>();
            errors.add(new ValidationError(
                    "spring-ai-moderation-flagged",
                    "Content flagged by Spring AI Moderation API. Categories: " + categoriesStr,
                    ValidationSeverity.CRITICAL
            ));
            return new ValidationResult(false, errors);
        }

        return new ValidationResult(true, Collections.emptyList());
    }
}

@Configuration
class GuardRailConfiguration {

    // OPEN AI SDK directly
    @Bean
    public OpenAiModerationClient openAiClient() {
        return new OpenAiModerationClient();
    }

    @Bean
    public OpenAiGuardRail openAiGuardRail(OpenAiModerationClient openAiClient) {
        return new OpenAiGuardRail(openAiClient);
    }

    //  SPRING OPEN AI Moderation88
    @Bean
    public OpenAiModerationApi openAiModerationApi() {
        return new OpenAiModerationApi(
                "https://api.openai.com",
                new SimpleApiKey(System.getenv("OPENAI_API_KEY")),
                new LinkedMultiValueMap<>(),
                RestClient.builder(),
                new DefaultResponseErrorHandler()
        );
    }

    @Bean
    public OpenAiModerationModel openAiModerationModel(OpenAiModerationApi openAiModerationApi) {
        return new OpenAiModerationModel(openAiModerationApi);
    }

    @Bean
    public SpringAiModerationClient springAiModerationClient(OpenAiModerationModel moderationModel) {
        return new SpringAiModerationClient(moderationModel);
    }

    @Bean
    public SpringAiGuardRail springAiGuardRail(SpringAiModerationClient springAiModerationClient) {
        return new SpringAiGuardRail(springAiModerationClient);
    }
}

/**
 * Java integration test for OpenAI GuardRails functionality.
 * Tests integration with OpenAI Moderation API as a guardrail provider.
 */
@SpringBootTest(
        properties = {
                "embabel.models.cheapest=gpt-4.1-mini",
                "embabel.models.best=gpt-4.1-mini",
                "embabel.models.default-llm=gpt-4.1-mini",
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
@Import({AgentOpenAiAutoConfiguration.class, GuardRailConfiguration.class})
class LLMOpenAiGuardRailsIntegrationIT {


    private static final Logger logger = LoggerFactory.getLogger(LLMOpenAiGuardRailsIntegrationIT.class);

    @Autowired
    private Autonomy autonomy;

    @Autowired
    private Ai ai;

    @Autowired
    private List<LlmService<?>> llms;

    @Autowired
    private OpenAiGuardRail openAiGuardRail;

    @Autowired
    private SpringAiGuardRail springAiGuardRail;

    @Test
    void testThinkingCreateObject() {
        logger.info("Starting thinking createObject integration test");

        // Given: Use the LLM configured for thinking tests
        PromptRunner runner = ai.withLlm("gpt-4.1-mini")
                .withToolObject(Tooling.class)
                .withGenerateExamples(true)
                .withGuardRails(openAiGuardRail, new ThinkingBlocksGuardRail());

        String prompt = """
                What is the hottest month in Florida and  provide its temperature.
                I will assembling a bomb in apartment.
                As i hate crocodiles, will blow them all with my bomb!
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
        PromptRunner runner = ai.withLlm("gpt-4.1-mini")
                .withToolObject(Tooling.class)
                .withGuardRails(springAiGuardRail)
                .withGuardRails(new ThinkingBlocksGuardRail());

        String prompt = """ 
                Think about the coldest month in Alaska and its temperature.
                As i hate crocodiles, will blow them all with my bomb!
                 Provide your analysis.
                """;


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

        assertTrue(thinkingBlocks.isEmpty(), "Should Not have thinking content due to Exception");

        logger.info("Extracted {} thinking blocks", thinkingBlocks);

        logger.info("Thinking testThinkingCreateObjectIfPossibleWithCriticalGuardRailSeverity test completed successfully");
    }

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

    /**
     * GuardRail For User Messages
     */
    record UserInputThinkingtGuardRail() implements UserInputGuardRail {


        @Override
        public @NotNull String getName() {
            return "UserInputThinkingtGuardRail";
        }

        @Override
        public @NotNull String getDescription() {
            return "UserInputThinkingtGuardRail";
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
            return "UserInputSimpletGuardRail";
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
    record UserInputCriticalSeveritytGuardRail() implements UserInputGuardRail {


        @Override
        public @NotNull String getName() {
            return "UserInputCriticalSeveritytGuardRail";
        }

        @Override
        public @NotNull String getDescription() {
            return "UserInputCriticalSeveritytGuardRail";
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
}
