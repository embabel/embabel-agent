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
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.api.event.LlmInvocationEvent;
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration;
import com.embabel.agent.core.LlmInvocation;
import com.embabel.agent.core.Usage;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thinking tokens and cost with a real OpenAI reasoning model.
 *
 * <p>OpenAI counts a reasoning model's reasoning inside its completion tokens, the convention embabel follows: no
 * converter adds to it, so {@link Usage#getThinkingTokens()} stays null and the cost is the prompt and the completion
 * at their prices.
 */
@SpringBootTest(
        properties = {
                "embabel.models.cheapest=gpt-5-mini",
                "embabel.models.best=gpt-5-mini",
                "embabel.models.default-llm=gpt-5-mini",
                "embabel.agent.platform.llm-operations.prompts.defaultTimeout=240s",
                "spring.main.allow-bean-definition-overriding=true",
        }
)
@ActiveProfiles("thinking-cost-it")
@ConfigurationPropertiesScan(basePackages = "com.embabel.agent")
@ComponentScan(basePackages = "com.embabel.agent")
@Import({AgentOpenAiAutoConfiguration.class, LLMOpenAiThinkingCostIT.InvocationListenerConfig.class})
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+",
        disabledReason = "Integration test requires OPENAI_API_KEY")
class LLMOpenAiThinkingCostIT {

    private static final Logger logger = LoggerFactory.getLogger(LLMOpenAiThinkingCostIT.class);

    private static final String MODEL = "gpt-5-mini";

    @Autowired
    private Ai ai;

    @Autowired
    private InvocationListener listener;

    @Test
    void reasoning_model_reports_its_reasoning_in_the_completion_and_costs_as_before() {
        listener.reset();

        String answer = ai.withLlm(MODEL).generateText(
                "A bat and a ball cost 1.10 dollars in total. The bat costs 1.00 dollar more than the ball. "
                        + "How many cents does the ball cost? Reply with the number only.");

        assertNotNull(answer, "The model must answer");
        List<LlmInvocation> invocations = listener.invocations();
        assertFalse(invocations.isEmpty(), "At least one LlmInvocationEvent must fire for the real call");

        for (LlmInvocation invocation : invocations) {
            Usage usage = invocation.getUsage();
            logger.info("{} usage: prompt={}, completion={}, thinking={}, cost={}",
                    MODEL, usage.getPromptTokens(), usage.getCompletionTokens(), usage.getThinkingTokens(), invocation.cost());
            assertTrue(usage.getCompletionTokens() > 0, "completionTokens must be > 0 for a real call");
            assertNull(usage.getThinkingTokens(), "OpenAI counts reasoning inside the completion: no thinking apart");
            double expected = invocation.getLlmMetadata().getPricingModel()
                    .costOf(usage.getPromptTokens(), usage.getCompletionTokens());
            assertEquals(expected, invocation.cost(), 1e-12, "The cost must be the prompt and the completion at their prices");
            assertTrue(invocation.cost() > 0.0, MODEL + " has a pricing model: the cost must be > 0");
        }
    }

    /** Spring config that exposes an {@link InvocationListener} bean, auto-discovered by embabel. */
    @Configuration
    static class InvocationListenerConfig {
        @Bean
        InvocationListener invocationListener() {
            return new InvocationListener();
        }
    }

    /** Keeps each {@link LlmInvocation} a real call records. */
    static class InvocationListener implements AgenticEventListener {

        private final List<LlmInvocation> received = new CopyOnWriteArrayList<>();

        @Override
        public void onProcessEvent(@NotNull AgentProcessEvent event) {
            if (event instanceof LlmInvocationEvent llm) {
                received.add(llm.getInvocation());
            }
        }

        List<LlmInvocation> invocations() {
            return List.copyOf(received);
        }

        void reset() {
            received.clear();
        }
    }
}
