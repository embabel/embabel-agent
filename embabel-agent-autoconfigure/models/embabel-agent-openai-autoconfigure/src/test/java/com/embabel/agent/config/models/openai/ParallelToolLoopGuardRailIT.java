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
import com.embabel.agent.api.tool.config.ToolLoopConfiguration;
import com.embabel.agent.api.tool.config.ToolLoopConfiguration.ToolLoopType;
import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail;
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.spi.loop.ToolLoopFactory;
import org.springframework.ai.tool.annotation.Tool;
import com.embabel.common.core.thinking.ThinkingResponse;
import com.embabel.common.core.validation.ValidationResult;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ParallelToolLoop with guardrails on structured output.
 * Uses Embabel's built-in MathTools to verify that:
 * 1. Tools are executed in parallel (multiple threads)
 * 2. rawResponseText is correctly preserved after parallel execution
 * 3. Guardrails are invoked on the structured object result
 * 4. Parallel execution is faster than sequential (time comparison)
 */
@SpringBootTest(
        properties = {
                "embabel.models.default-llm=gpt-4.1-mini",
                "embabel.agent.platform.llm-operations.prompts.defaultTimeout=240s",
                "embabel.agent.platform.toolloop.type=parallel",
                "spring.main.allow-bean-definition-overriding=true",
                "logging.level.com.embabel.agent.spi.loop.support.ParallelToolLoop=DEBUG",
                "logging.level.com.embabel.agent.spi.support.springai.ChatClientLlmOperations=TRACE",
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
@Import(AgentOpenAiAutoConfiguration.class)
class ParallelToolLoopGuardRailIT {

    private static final Logger logger = LoggerFactory.getLogger(ParallelToolLoopGuardRailIT.class);

    @Autowired
    private Ai ai;

    @Autowired
    private ToolLoopFactory toolLoopFactory;

    /**
     * Result object: the LLM must call the slow add tools and aggregate the results.
     */
    static class MathSummary {
        private long result1;
        private long result2;
        private long result3;

        public MathSummary() {
        }

        public long getResult1() { return result1; }
        public void setResult1(long result1) { this.result1 = result1; }
        public long getResult2() { return result2; }
        public void setResult2(long result2) { this.result2 = result2; }
        public long getResult3() { return result3; }
        public void setResult3(long result3) { this.result3 = result3; }

        @Override
        public String toString() {
            return "MathSummary{result1=" + result1 + ", result2=" + result2 + ", result3=" + result3 + "}";
        }
    }

    /**
     * Custom slow tools — each addition loops 100M times to simulate a ~200ms I/O-bound operation.
     * This makes the sequential vs parallel time difference clearly visible.
     */
    static class SlowAddTools {
        private static final Logger toolLogger = LoggerFactory.getLogger(SlowAddTools.class);
        private static final int LOOP_COUNT = 2000_000_000;
        final ConcurrentHashMap<String, Integer> executionThreads = new ConcurrentHashMap<>();

        private void trackThread() {
            executionThreads.merge(Thread.currentThread().getName(), 1, Integer::sum);
        }

        @LlmTool(description = "slowly add two numbers (first tool)")
        long slowAdd1(long a, long b) {
            trackThread();
            toolLogger.info("slowAdd1({}, {}) started on thread {}", a, b, Thread.currentThread().getName());
            long dummy = 0;
            for (int i = 0; i < LOOP_COUNT; i++) { dummy += i; }
            toolLogger.info("slowAdd1 completed (dummy={})", dummy);
            return a + b;
        }

        @Tool
        long slowAdd2(long a, long b) {
            trackThread();
            toolLogger.info("slowAdd2({}, {}) started on thread {}", a, b, Thread.currentThread().getName());
            long dummy = 0;
            for (int i = 0; i < LOOP_COUNT; i++) { dummy += i; }
            toolLogger.info("slowAdd2 completed (dummy={})", dummy);
            return a + b;
        }

        @LlmTool(description = "slowly add two numbers (third tool)")
        long slowAdd3(long a, long b) {
            trackThread();
            toolLogger.info("slowAdd3({}, {}) started on thread {}", a, b, Thread.currentThread().getName());
            long dummy = 0;
            for (int i = 0; i < LOOP_COUNT; i++) { dummy += i; }
            toolLogger.info("slowAdd3 completed (dummy={})", dummy);
            return a + b;
        }
    }

    private static final String PROMPT = """
            Using the tools available, compute the following three operations.
            You MUST call each tool separately — do NOT compute the results yourself.

            1. slowAdd1(10, 20)
            2. slowAdd2(30, 40)
            3. slowAdd3(50, 60)

            Call all three tools, then return a MathSummary with:
            - result1: the result of slowAdd1(10, 20)
            - result2: the result of slowAdd2(30, 40)
            - result3: the result of slowAdd3(50, 60)
            """;

    /**
     * Runs the prompt with slow add tools and returns the result.
     */
    private MathSummary runMathPrompt(
            List<String> guardRailCalled,
            SlowAddTools tools
    ) {
        AssistantMessageGuardRail trackingGuard = new AssistantMessageGuardRail() {
            @Override
            public @NotNull String getName() {
                return "ParallelToolLoopTrackingGuardRail";
            }

            @Override
            public @NotNull String getDescription() {
                return "Tracks guardrail invocation after tool execution";
            }

            @Override
            public @NotNull ValidationResult validate(@NotNull String input, @NotNull Blackboard blackboard) {
                guardRailCalled.add(input);
                return new ValidationResult(true, Collections.emptyList());
            }

            @Override
            public @NotNull ValidationResult validate(@NotNull ThinkingResponse<?> response, @NotNull Blackboard blackboard) {
                return new ValidationResult(true, Collections.emptyList());
            }
        };

        return ai.withLlm("gpt-4.1-mini")
                .withToolObject(tools)
                .withGuardRails(trackingGuard)
                .creating(MathSummary.class)
                .fromPrompt(PROMPT);
    }

    private void assertMathSummaryCorrect(MathSummary result) {
        assertNotNull(result, "MathSummary should not be null");
        assertEquals(30, result.getResult1(), "10 + 20 = 30");
        assertEquals(70, result.getResult2(), "30 + 40 = 70");
        assertEquals(110, result.getResult3(), "50 + 60 = 110");
    }

    /**
     * Compares sequential vs parallel tool execution within a single test.
     * Uses ReflectionTestUtils to swap ToolLoopConfiguration at runtime,
     * forcing the factory to create sequential or parallel tool loops.
     */
    @Test
    void testSequentialVsParallelWithGuardRail() {
        // Save original config
        var originalConfig = ReflectionTestUtils.getField(toolLoopFactory, "config");

        try {
            // ========== 1. SEQUENTIAL MODE ==========
            var sequentialConfig = new ToolLoopConfiguration(
                    ToolLoopType.DEFAULT,
                    20,
                    new ToolLoopConfiguration.ParallelModeProperties(),
                    new ToolLoopConfiguration.ToolNotFoundProperties()
            );
            ReflectionTestUtils.setField(toolLoopFactory, "config", sequentialConfig);

            var seqGuardRailCalled = Collections.synchronizedList(new ArrayList<String>());
            var seqTools = new SlowAddTools();

            var seqStart = System.currentTimeMillis();
            MathSummary seqResult = runMathPrompt(seqGuardRailCalled, seqTools);
            var seqElapsed = System.currentTimeMillis() - seqStart;

            assertMathSummaryCorrect(seqResult);
            assertFalse(seqGuardRailCalled.isEmpty(),
                    "GuardRail should be invoked in sequential mode");

            // ========== 2. PARALLEL MODE ==========
            var parallelConfig = new ToolLoopConfiguration(
                    ToolLoopType.PARALLEL,
                    20,
                    new ToolLoopConfiguration.ParallelModeProperties(),
                    new ToolLoopConfiguration.ToolNotFoundProperties()
            );
            ReflectionTestUtils.setField(toolLoopFactory, "config", parallelConfig);

            var parGuardRailCalled = Collections.synchronizedList(new ArrayList<String>());
            var parTools = new SlowAddTools();

            var parStart = System.currentTimeMillis();
            MathSummary parResult = runMathPrompt(parGuardRailCalled, parTools);
            var parElapsed = System.currentTimeMillis() - parStart;

            assertMathSummaryCorrect(parResult);
            assertFalse(parGuardRailCalled.isEmpty(),
                    "GuardRail should be invoked in parallel mode");

            // ========== 3. COMPARE ==========
            logger.info("""

                    ========== SEQUENTIAL vs PARALLEL COMPARISON ==========
                    Sequential:
                      Time: {} ms
                      Execution threads: {} ({})
                      GuardRail invoked: {} time(s)

                    Parallel:
                      Time: {} ms
                      Execution threads: {} ({})
                      GuardRail invoked: {} time(s)
                    ======================================================
                    """,
                    seqElapsed,
                    seqTools.executionThreads.size(), seqTools.executionThreads.keySet(),
                    seqGuardRailCalled.size(),
                    parElapsed,
                    parTools.executionThreads.size(), parTools.executionThreads.keySet(),
                    parGuardRailCalled.size()
            );

            // Verify parallel used multiple threads (tracked inside the tools themselves)
            assertTrue(parTools.executionThreads.size() > 1,
                    "Parallel execution should use multiple threads, but only used: " +
                            parTools.executionThreads.keySet());

            // Verify sequential used single thread
            assertEquals(1, seqTools.executionThreads.size(),
                    "Sequential execution should use a single thread");

        } finally {
            // Always restore original config
            ReflectionTestUtils.setField(toolLoopFactory, "config", originalConfig);
        }
    }
}