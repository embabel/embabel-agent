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
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration;
import com.embabel.common.ai.model.LlmOptions;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Optional;

import static com.embabel.common.ai.model.NativeStructuredOutputMode.DEFAULT;
import static com.embabel.common.ai.model.NativeStructuredOutputMode.ENABLED;
import static com.embabel.common.ai.model.NativeStructuredOutputModeKt.withNativeStructuredOutput;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for OpenAI native structured output via the response_format API.
 * Requires a live OPENAI_API_KEY. Uses the default configured LLM (gpt-4.1-mini).
 */
@SpringBootTest(
        classes = LLMOpenAiGuardRailsIntegrationIT.TestApplication.class,
    properties = {
        "embabel.models.cheapest=gpt-4.1-mini",
        "embabel.models.best=gpt-4.1-mini",
        "embabel.models.default-llm=gpt-4.1-mini",
        "embabel.agent.platform.llm-operations.prompts.defaultTimeout=120s",
        "logging.level.com.embabel.agent.spi.support.springai.ChatClientLlmOperations=TRACE",
        "logging.level.com.embabel.common.ai.converters.jsonSchemaSupport=DEBUG",
        "logging.level.com.embabel.agent.config.models.openai.OpenAiNativeStructuredOutputConfigurer=DEBUG",
        "logging.level.org.springframework.ai.openai=TRACE",
        "logging.level.org.springframework.ai.chat=DEBUG",
        "logging.level.com.openai=TRACE"
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
@Import({AgentOpenAiAutoConfiguration.class})
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+",
        disabledReason = "Integration test requires OPENAI_API_KEY")
class OpenAiNativeStructuredOutputIT {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiNativeStructuredOutputIT.class);

    @Autowired
    private Ai ai;

    private PromptRunner nativeRunner() {
        var options = LlmOptions.withDefaultLlm();
        options = withNativeStructuredOutput(options, ENABLED);
        return ai.withLlm(options);
    }

    // ── Data classes ────────────────────────────────────────────────────────

    static class CapitalAnswer {
        @JsonProperty(required = true)
        public String capital;
        @JsonProperty(required = true)
        public String country;
    }

    static class PersonWithAge {
        @JsonProperty(required = true)
        public String name;
        @JsonProperty(required = true)
        public int age;
        @JsonProperty(required = true)
        public String occupation;
        @JsonProperty(required = true)
        public Optional<String> nickname;  // optional — ["string","null"] in required[]
    }

    static class TeamRoster {
        @JsonProperty(required = true)
        public String teamName;
        @JsonProperty(required = true)
        public List<PersonWithAge> members;
    }

    // ── Flat object ──────────────────────────────────────────────────────────

    @Test
    void extractsFlatObjectViaNativeStructuredOutput() {
        CapitalAnswer result = nativeRunner().createObject(
                "France's capital is Paris. Extract the capital city and country.",
                CapitalAnswer.class
        );

        assertNotNull(result, "Expected non-null CapitalAnswer");
        assertEquals("France", result.country, "country mismatch: " + result.country);
        assertTrue(result.capital.contains("Paris"), "capital mismatch: " + result.capital);
        logger.info("Flat object result: capital={}, country={}", result.capital, result.country);
    }

    @Test
    void flatObjectNativeResultMatchesPromptBasedResult() {
        String prompt = "Alice is 32 years old and works as an engineer. Extract name, age, and occupation.";

        PersonWithAge nativeResult = nativeRunner().createObject(prompt, PersonWithAge.class);
        PersonWithAge promptResult = ai.withDefaultLlm().createObject(prompt, PersonWithAge.class);

        assertNotNull(nativeResult, "Native result should not be null");
        assertNotNull(promptResult, "Prompt result should not be null");
        assertEquals("Alice", nativeResult.name, "native name mismatch: " + nativeResult.name);
        assertEquals(32, nativeResult.age, "native age mismatch: " + nativeResult.age);
        // nickname is optional — may be null or present; either is valid
        logger.info("Native: name={} age={} nickname={} | Prompt: name={} age={} nickname={}",
                nativeResult.name, nativeResult.age, nativeResult.nickname,
                promptResult.name, promptResult.age, promptResult.nickname);
    }

    // ── Array of objects ─────────────────────────────────────────────────────

    @Test
    void extractsListOfObjectsViaNativeStructuredOutput() {
        TeamRoster result = nativeRunner().createObject(
                "The Avengers team has: Tony Stark, age 45, engineer. Steve Rogers, age 105, soldier. " +
                        "Extract the team name and members.",
                TeamRoster.class
        );

        assertNotNull(result, "Expected non-null TeamRoster");
        assertNotNull(result.members, "Expected non-null members list");
        assertTrue(result.members.size() >= 2, "Expected at least 2 members, got: " + result.members.size());
        assertTrue(
                result.members.stream().anyMatch(m ->
                        m.name != null && (m.name.contains("Stark") || m.name.contains("Tony"))
                ),
                "Expected Tony Stark in members"
        );
        logger.info("TeamRoster: {} with {} members", result.teamName, result.members.size());
    }

    // ── DEFAULT mode ──────────────────────────────────────────────────────────

    @Test
    void defaultModeUsesNativePathForCompatibleSchema() {
        var options = LlmOptions.withDefaultLlm();
        options = withNativeStructuredOutput(options, DEFAULT);
        PromptRunner defaultRunner = ai.withLlm(options);

        CapitalAnswer result = defaultRunner.createObject(
                "Germany's capital is Berlin. Extract the capital city and country.",
                CapitalAnswer.class
        );

        assertNotNull(result, "Expected non-null CapitalAnswer");
        assertEquals("Germany", result.country, "country mismatch: " + result.country);
        assertTrue(result.capital.contains("Berlin"), "capital mismatch: " + result.capital);
        logger.info("DEFAULT mode result: capital={}, country={}", result.capital, result.country);
    }
}
