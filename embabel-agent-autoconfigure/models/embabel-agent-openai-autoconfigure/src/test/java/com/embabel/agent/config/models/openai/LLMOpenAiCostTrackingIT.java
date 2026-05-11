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
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.api.event.LlmInvocationEvent;
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for the per-call billing event flow.
 *
 * <p>Boots the full Spring context with OpenAI, invokes a real LLM, and
 * verifies that the {@link CostListener} bean — registered as a regular
 * {@link AgenticEventListener} — receives one {@link LlmInvocationEvent}
 * per LLM call, each carrying non-zero token usage and a matching
 * {@code interactionId}. Aggregating {@code event.invocation.cost()} gives a
 * total greater than zero (real pricing model) — this is the use case
 * described in issue #1604 (organisation-level cost tracking).
 */
@SpringBootTest(
        properties = {
                "embabel.models.cheapest=gpt-4.1-mini",
                "embabel.models.best=gpt-4.1-mini",
                "embabel.models.default-llm=gpt-4.1-mini",
                "embabel.agent.platform.llm-operations.prompts.defaultTimeout=240s",
                "spring.main.allow-bean-definition-overriding=true",
        }
)
@ActiveProfiles("cost-tracking-it")
@ConfigurationPropertiesScan(basePackages = "com.embabel.agent")
@ComponentScan(basePackages = "com.embabel.agent")
@Import({AgentOpenAiAutoConfiguration.class, LLMOpenAiCostTrackingIT.CostListenerConfig.class})
class LLMOpenAiCostTrackingIT {

    private static final Logger logger = LoggerFactory.getLogger(LLMOpenAiCostTrackingIT.class);

    @Autowired
    private Ai ai;

    @Autowired
    private CostListener costListener;

    @Test
    void llm_call_emits_one_LlmInvocationEvent_with_real_usage_and_cost() {
        costListener.reset();

        PromptRunner runner = ai.withLlm("gpt-4.1-mini");

        WeatherFact result = runner.createObject(
                "What is a typical July high temperature in Paris in Celsius? "
                        + "Return only the city and temperature.",
                WeatherFact.class
        );

        assertNotNull(result, "Agent must return a non-null result");
        assertNotNull(result.getCity(), "Result must have a city");

        // The whole point: at least one LlmInvocationEvent must have fired
        List<LlmInvocationEvent> events = costListener.events();
        assertFalse(events.isEmpty(),
                "CostListener must receive at least one LlmInvocationEvent for the real LLM call");
        logger.info("Received {} LlmInvocationEvent(s)", events.size());

        // Each event carries real usage data
        for (LlmInvocationEvent ev : events) {
            Integer pt = ev.getInvocation().getUsage().getPromptTokens();
            Integer ct = ev.getInvocation().getUsage().getCompletionTokens();
            assertNotNull(pt, "promptTokens must be present");
            assertNotNull(ct, "completionTokens must be present");
            assertTrue(pt > 0, "promptTokens must be > 0 for a real call");
            assertTrue(ct > 0, "completionTokens must be > 0 for a real call");
            assertNotNull(ev.getInteractionId(), "interactionId must be set");
            assertNotNull(ev.getAgentProcess(), "agentProcess must be present");
            assertNotNull(ev.getInvocation().getLlmMetadata().getName(),
                    "model name must be reported");
        }

        // Aggregating cost across all events gives the per-request bill
        double totalCost = costListener.totalCostUsd();
        long totalCalls = costListener.totalCalls();
        long totalPrompt = costListener.totalPromptTokens();
        long totalCompletion = costListener.totalCompletionTokens();

        logger.info("Total: calls={}, prompt={}, completion={}, cost=${}",
                totalCalls, totalPrompt, totalCompletion, totalCost);

        assertEquals(events.size(), totalCalls,
                "Aggregated call count must equal number of events");
        assertTrue(totalPrompt > 0, "Total prompt tokens must be > 0");
        assertTrue(totalCompletion > 0, "Total completion tokens must be > 0");
        assertTrue(totalCost > 0.0,
                "Total cost must be > 0 (gpt-4.1-mini has a non-null pricing model)");
    }

    /** Spring config that exposes a {@link CostListener} bean — auto-discovered by embabel. */
    @Configuration
    static class CostListenerConfig {
        @Bean
        CostListener costListener() {
            return new CostListener();
        }
    }

    /**
     * Reference implementation of the use case from #1604: an organisation-level
     * cost tracker subscribing to {@link LlmInvocationEvent} and aggregating
     * tokens / cost across calls. Thread-safe (CONCURRENT mode would emit
     * events from multiple threads).
     */
    static class CostListener implements AgenticEventListener {

        private final List<LlmInvocationEvent> received = new CopyOnWriteArrayList<>();
        private final LongAdder promptTokens = new LongAdder();
        private final LongAdder completionTokens = new LongAdder();
        private final DoubleAdder costUsd = new DoubleAdder();
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public void onProcessEvent(@NotNull AgentProcessEvent event) {
            if (event instanceof LlmInvocationEvent llm) {
                received.add(llm);
                Integer pt = llm.getInvocation().getUsage().getPromptTokens();
                Integer ct = llm.getInvocation().getUsage().getCompletionTokens();
                promptTokens.add(pt != null ? pt : 0);
                completionTokens.add(ct != null ? ct : 0);
                costUsd.add(llm.getInvocation().cost());
                callCount.incrementAndGet();
            }
        }

        List<LlmInvocationEvent> events() {
            return List.copyOf(received);
        }

        long totalCalls() {
            return callCount.get();
        }

        long totalPromptTokens() {
            return promptTokens.sum();
        }

        long totalCompletionTokens() {
            return completionTokens.sum();
        }

        double totalCostUsd() {
            return costUsd.sum();
        }

        void reset() {
            received.clear();
            promptTokens.reset();
            completionTokens.reset();
            costUsd.reset();
            callCount.set(0);
        }
    }

    /** Output type for the LLM call. */
    public static class WeatherFact {
        private String city;
        private Integer celsius;

        public WeatherFact() {}

        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }

        public Integer getCelsius() { return celsius; }
        public void setCelsius(Integer celsius) { this.celsius = celsius; }
    }
}
