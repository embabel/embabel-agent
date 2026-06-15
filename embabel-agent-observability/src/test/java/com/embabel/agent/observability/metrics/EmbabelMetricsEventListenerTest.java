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
package com.embabel.agent.observability.metrics;

import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.event.*;
import com.embabel.agent.api.event.observation.ToolCallOutcomes;
import com.embabel.agent.core.*;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.common.ai.model.LlmMetadata;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link EmbabelMetricsEventListener}.
 *
 * @since 0.3.4
 */
@ExtendWith(MockitoExtension.class)
class EmbabelMetricsEventListenerTest {

    // ================================================================================
    // ACTIVE AGENT GAUGE TESTS
    // ================================================================================

    @Nested
    @DisplayName("Active Agent Gauge Tests")
    class ActiveAgentGaugeTests {

        @Test
        @DisplayName("Creation should increment active agents gauge")
        void creation_shouldIncrementGauge() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));

            Gauge gauge = registry.find("embabel.agent.active").gauge();
            assertThat(gauge).isNotNull();
            assertThat(gauge.value()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Completed should decrement active agents gauge")
        void completed_shouldDecrementGauge() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "TestAgent");
            mockUsageAndCost(process, null, 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Failed should decrement active agents gauge")
        void failed_shouldDecrementGauge() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "TestAgent");
            mockUsageAndCost(process, null, 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessFailedEvent(process));

            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Killed should decrement active agents gauge")
        void killed_shouldDecrementGauge() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new ProcessKilledEvent(process));

            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Multiple simultaneous agents should be tracked correctly")
        void multipleAgents_shouldTrackCorrectly() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process1 = createMockAgentProcess("run-1", "Agent1");
            var process2 = createMockAgentProcess("run-2", "Agent2");
            var process3 = createMockAgentProcess("run-3", "Agent3");
            mockUsageAndCost(process1, null, 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process1));
            listener.onProcessEvent(new AgentProcessCreationEvent(process2));
            listener.onProcessEvent(new AgentProcessCreationEvent(process3));
            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(3.0);

            listener.onProcessEvent(new AgentProcessCompletedEvent(process1));
            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("Stuck is non-terminal: agent stays in the active gauge (may resume)")
        void stuck_keepsAgentActive() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessStuckEvent(process));

            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Waiting is non-terminal: agent stays in the active gauge (may resume)")
        void waiting_keepsAgentActive() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessWaitingEvent(process));

            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Paused is non-terminal: agent stays in the active gauge (may resume)")
        void paused_keepsAgentActive() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessPausedEvent(process));

            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Human-in-the-loop: process resumes after WAITING and is decremented only on completion")
        void waitingThenComplete_decrementsOnlyOnTerminal() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "TestAgent");
            mockUsageAndCost(process, null, 0.0);

            // Created and running
            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            // Asks the user a question and waits — no new creation event is ever emitted on resume
            listener.onProcessEvent(new AgentProcessWaitingEvent(process));
            // Still counted as a live process while it waits for / processes the answer
            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(1.0);

            // Only the terminal event removes it
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));
            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Duplicate terminal events keep the gauge at zero (idempotent, never negative)")
        void duplicateTerminal_staysAtZero() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "TestAgent");
            mockUsageAndCost(process, null, 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process)); // second terminal for same id

            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(0.0);
        }
    }

    // ================================================================================
    // AGENT ERROR COUNTER TESTS
    // ================================================================================

    @Nested
    @DisplayName("Agent Error Counter Tests")
    class AgentErrorCounterTests {

        @Test
        @DisplayName("Failed agent should increment error counter with agent tag")
        void failed_shouldIncrementErrorCounter() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "CustomerAgent");
            mockUsageAndCost(process, null, 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessFailedEvent(process));

            Counter counter = registry.find("embabel.agent.errors.total").tag("agent", "CustomerAgent").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }

    // ================================================================================
    // LLM TOKEN COUNTER TESTS
    // ================================================================================

    @Nested
    @DisplayName("LLM Token Counter Tests")
    class LlmTokenCounterTests {

        @Test
        @DisplayName("Completed agent should record prompt and completion tokens")
        void completed_shouldRecordTokens() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "TokenAgent");
            mockUsageAndCost(process, new Usage(100, 50, null), 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            Counter inputCounter = registry.find("embabel.llm.tokens.total")
                    .tag("agent", "TokenAgent").tag("direction", "input").counter();
            assertThat(inputCounter).isNotNull();
            assertThat(inputCounter.count()).isEqualTo(100.0);

            Counter outputCounter = registry.find("embabel.llm.tokens.total")
                    .tag("agent", "TokenAgent").tag("direction", "output").counter();
            assertThat(outputCounter).isNotNull();
            assertThat(outputCounter.count()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("Null usage should not record tokens")
        void nullUsage_shouldNotRecordTokens() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "NullAgent");
            mockUsageAndCost(process, null, 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            assertThat(registry.find("embabel.llm.tokens.total").counter()).isNull();
        }

        @Test
        @DisplayName("Null token values should not record tokens")
        void nullTokenValues_shouldNotRecordTokens() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "NullTokenAgent");
            mockUsageAndCost(process, new Usage(null, null, null), 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            assertThat(registry.find("embabel.llm.tokens.total").counter()).isNull();
        }
    }

    // ================================================================================
    // LLM COST COUNTER TESTS
    // ================================================================================

    @Nested
    @DisplayName("LLM Cost Counter Tests")
    class LlmCostCounterTests {

        @Test
        @DisplayName("Completed agent should record cost with agent tag")
        void completed_shouldRecordCost() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "CostAgent");
            mockUsageAndCost(process, new Usage(100, 50, null), 0.0042);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            Counter counter = registry.find("embabel.llm.cost.total").tag("agent", "CostAgent").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(0.0042);
        }

        @Test
        @DisplayName("Zero cost should not record")
        void zeroCost_shouldNotRecord() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "FreeCostAgent");
            mockUsageAndCost(process, new Usage(100, 50, null), 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            assertThat(registry.find("embabel.llm.cost.total").counter()).isNull();
        }
    }

    // ================================================================================
    // TOOL ERROR COUNTER TESTS
    // ================================================================================

    @Nested
    @DisplayName("Tool Error Counter Tests")
    class ToolErrorCounterTests {

        @Test
        @DisplayName("Tool failure should increment tool error counter")
        void toolFailure_shouldIncrementCounter() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "ToolAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));

            var toolResponseEvent = createToolCallResponseEvent(process, "WebSearch");
            try (MockedStatic<ToolCallOutcomes> outcomes = mockStatic(ToolCallOutcomes.class)) {
                outcomes.when(() -> ToolCallOutcomes.error(toolResponseEvent))
                        .thenReturn(new RuntimeException("search failed"));
                listener.onProcessEvent(toolResponseEvent);
            }

            Counter counter = registry.find("embabel.tool.errors.total").tag("tool", "WebSearch").tag("agent", "ToolAgent").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Tool success should not increment tool error counter")
        void toolSuccess_shouldNotIncrementCounter() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "ToolAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));

            var toolResponseEvent = createToolCallResponseEvent(process, "WebSearch");
            try (MockedStatic<ToolCallOutcomes> outcomes = mockStatic(ToolCallOutcomes.class)) {
                outcomes.when(() -> ToolCallOutcomes.error(toolResponseEvent)).thenReturn(null);
                listener.onProcessEvent(toolResponseEvent);
            }

            assertThat(registry.find("embabel.tool.errors.total").counter()).isNull();
        }
    }

    // ================================================================================
    // REPLANNING COUNTER TESTS
    // ================================================================================

    @Nested
    @DisplayName("Replanning Counter Tests")
    class ReplanningCounterTests {

        @Test
        @DisplayName("Replan requested should increment replanning counter with agent tag")
        void replanRequested_shouldIncrementCounter() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "PlanAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new ReplanRequestedEvent(process, "action failed"));

            Counter counter = registry.find("embabel.planning.replanning.total").tag("agent", "PlanAgent").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }

    // ================================================================================
    // AGENT DURATION TIMER TESTS
    // ================================================================================

    @Nested
    @DisplayName("Agent Duration Timer Tests")
    class AgentDurationTimerTests {

        @Test
        @DisplayName("Completed agent should record duration with status=completed")
        void completed_shouldRecordDuration() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "DurationAgent");
            mockUsageAndCost(process, null, 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            Timer timer = registry.find("embabel.agent.duration")
                    .tag("agent", "DurationAgent").tag("status", "completed").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Failed agent should record duration with status=failed")
        void failed_shouldRecordDuration() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "DurationAgent");
            mockUsageAndCost(process, null, 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessFailedEvent(process));

            Timer timer = registry.find("embabel.agent.duration")
                    .tag("agent", "DurationAgent").tag("status", "failed").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Early termination should record duration with status=terminated")
        void earlyTermination_shouldRecordDuration() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "DurationAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new EarlyTermination(
                    process, true, "budget exceeded", mock(EarlyTerminationPolicy.class)));

            Timer timer = registry.find("embabel.agent.duration")
                    .tag("agent", "DurationAgent").tag("status", "terminated").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Active duration should sum action running times, excluding idle/wait time")
        void completed_shouldRecordActiveDurationFromHistory() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "DurationAgent");
            mockUsageAndCost(process, null, 0.0);
            when(process.getHistory()).thenReturn(List.of(
                    new ActionInvocation("a1", Instant.now(), Duration.ofMillis(100)),
                    new ActionInvocation("a2", Instant.now(), Duration.ofMillis(150))));

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            Timer timer = registry.find("embabel.agent.active_duration")
                    .tag("agent", "DurationAgent").tag("status", "completed").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250.0);
        }

        @Test
        @DisplayName("Active duration is recorded as zero when there is no action history")
        void completed_shouldRecordZeroActiveDurationWhenNoHistory() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "DurationAgent");
            mockUsageAndCost(process, null, 0.0);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            Timer timer = registry.find("embabel.agent.active_duration")
                    .tag("agent", "DurationAgent").tag("status", "completed").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Early termination should purge the creation timestamp (no leak)")
        void earlyTermination_shouldPurgeTimestamp() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "DurationAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new EarlyTermination(
                    process, true, "budget exceeded", mock(EarlyTerminationPolicy.class)));
            // A second terminal event for the same process must not re-record:
            // the timestamp was purged on the first, proving no entry lingers.
            listener.onProcessEvent(new EarlyTermination(
                    process, true, "budget exceeded", mock(EarlyTerminationPolicy.class)));

            Timer timer = registry.find("embabel.agent.duration")
                    .tag("agent", "DurationAgent").tag("status", "terminated").timer();
            assertThat(timer.count()).isEqualTo(1);
        }
    }

    // ================================================================================
    // LLM REQUEST COUNTER TESTS
    // ================================================================================

    @Nested
    @DisplayName("LLM Request Counter Tests")
    class LlmRequestCounterTests {

        @Test
        @DisplayName("LLM request should increment counter with agent and model tags")
        void llmRequest_shouldIncrementCounter() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "LlmAgent");

            listener.onProcessEvent(createMockLlmRequestEvent(process, "gpt-4"));

            Counter counter = registry.find("embabel.llm.requests.total")
                    .tag("agent", "LlmAgent").tag("model", "gpt-4").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }

    // ================================================================================
    // LLM DURATION TIMER TESTS
    // ================================================================================

    @Nested
    @DisplayName("LLM Duration Timer Tests")
    class LlmDurationTimerTests {

        @Test
        @DisplayName("LLM response should record duration with model and agent tags")
        void llmResponse_shouldRecordDuration() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "LlmAgent");

            listener.onProcessEvent(createMockLlmResponseEvent(process, "gpt-4", Duration.ofMillis(250)));

            Timer timer = registry.find("embabel.llm.duration")
                    .tag("model", "gpt-4").tag("agent", "LlmAgent").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(250.0);
        }
    }

    // ================================================================================
    // TOOL DURATION TIMER TESTS
    // ================================================================================

    @Nested
    @DisplayName("Tool Duration Timer Tests")
    class ToolDurationTimerTests {

        @Test
        @DisplayName("Tool response should record duration with tool and agent tags")
        void toolResponse_shouldRecordDuration() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "ToolAgent");

            var toolResponseEvent = createToolCallResponseEvent(process, "WebSearch");
            try (MockedStatic<ToolCallOutcomes> outcomes = mockStatic(ToolCallOutcomes.class)) {
                outcomes.when(() -> ToolCallOutcomes.error(toolResponseEvent)).thenReturn(null);
                listener.onProcessEvent(toolResponseEvent);
            }

            Timer timer = registry.find("embabel.tool.duration")
                    .tag("tool", "WebSearch").tag("agent", "ToolAgent").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(50.0);
        }
    }

    // ================================================================================
    // TOOL CALL COUNTER TESTS
    // ================================================================================

    @Nested
    @DisplayName("Tool Call Counter Tests")
    class ToolCallCounterTests {

        @Test
        @DisplayName("Tool call request should increment counter with tool and agent tags")
        void toolCallRequest_shouldIncrementCounter() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "ToolAgent");

            listener.onProcessEvent(createMockToolCallRequestEvent(process, "WebSearch"));

            Counter counter = registry.find("embabel.tool.calls.total")
                    .tag("tool", "WebSearch").tag("agent", "ToolAgent").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }

    // ================================================================================
    // AGENT STUCK COUNTER TESTS
    // ================================================================================

    @Nested
    @DisplayName("Agent Stuck Counter Tests")
    class AgentStuckCounterTests {

        @Test
        @DisplayName("Agent stuck should increment counter with agent tag")
        void agentStuck_shouldIncrementCounter() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "StuckAgent");

            listener.onProcessEvent(new AgentProcessStuckEvent(process));

            Counter counter = registry.find("embabel.agent.stuck.total")
                    .tag("agent", "StuckAgent").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }

    // ================================================================================
    // TOOL LOOP ITERATIONS TESTS
    // ================================================================================

    @Nested
    @DisplayName("Tool Loop Iterations Tests")
    class ToolLoopIterationsTests {

        @Test
        @DisplayName("Tool loop completed should record iteration count with agent tag")
        void toolLoopCompleted_shouldRecordIterations() {
            var registry = new SimpleMeterRegistry();
            var listener = new EmbabelMetricsEventListener(registry, new ObservabilityProperties());
            var process = createMockAgentProcess("run-1", "LoopAgent");

            listener.onProcessEvent(createMockToolLoopCompletedEvent(process, 5));

            DistributionSummary summary = registry.find("embabel.tool_loop.iterations")
                    .tag("agent", "LoopAgent").summary();
            assertThat(summary).isNotNull();
            assertThat(summary.count()).isEqualTo(1);
            assertThat(summary.totalAmount()).isEqualTo(5.0);
        }
    }

    // ================================================================================
    // METRICS DISABLED TESTS
    // ================================================================================

    @Nested
    @DisplayName("Metrics Disabled Tests")
    class MetricsDisabledTests {

        @Test
        @DisplayName("No metrics should be recorded when metricsEnabled is false")
        void noMetrics_whenDisabled() {
            var registry = new SimpleMeterRegistry();
            var properties = new ObservabilityProperties();
            properties.setMetricsEnabled(false);
            var listener = new EmbabelMetricsEventListener(registry, properties);
            var process = createMockAgentProcess("run-1", "TestAgent");
            mockUsageAndCost(process, new Usage(100, 50, null), 0.0042);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new ReplanRequestedEvent(process, "reason"));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            // Gauge is registered in constructor, but the value should stay at 0
            assertThat(registry.find("embabel.agent.active").gauge().value()).isEqualTo(0.0);
            assertThat(registry.find("embabel.agent.errors.total").counter()).isNull();
            assertThat(registry.find("embabel.llm.tokens.total").counter()).isNull();
            assertThat(registry.find("embabel.llm.cost.total").counter()).isNull();
            assertThat(registry.find("embabel.planning.replanning.total").counter()).isNull();
            assertThat(registry.find("embabel.agent.duration").timer()).isNull();
            assertThat(registry.find("embabel.llm.requests.total").counter()).isNull();
            assertThat(registry.find("embabel.llm.duration").timer()).isNull();
            assertThat(registry.find("embabel.tool.calls.total").counter()).isNull();
            assertThat(registry.find("embabel.tool.duration").timer()).isNull();
            assertThat(registry.find("embabel.agent.stuck.total").counter()).isNull();
            assertThat(registry.find("embabel.tool_loop.iterations").summary()).isNull();
        }
    }

    // ================================================================================
    // HELPER METHODS
    // ================================================================================

    private static AgentProcess createMockAgentProcess(String runId, String agentName) {
        AgentProcess process = mock(AgentProcess.class);
        Agent agent = mock(Agent.class);
        Blackboard blackboard = mock(Blackboard.class);
        ProcessOptions processOptions = mock(ProcessOptions.class);
        Goal goal = mock(Goal.class);

        lenient().when(process.getId()).thenReturn(runId);
        lenient().when(process.getAgent()).thenReturn(agent);
        lenient().when(process.getBlackboard()).thenReturn(blackboard);
        lenient().when(process.getProcessOptions()).thenReturn(processOptions);
        lenient().when(process.getParentId()).thenReturn(null);
        lenient().when(process.getGoal()).thenReturn(goal);
        lenient().when(process.getFailureInfo()).thenReturn(null);

        lenient().when(agent.getName()).thenReturn(agentName);
        lenient().when(agent.getGoals()).thenReturn(Set.of(goal));
        lenient().when(goal.getName()).thenReturn("TestGoal");

        lenient().when(blackboard.getObjects()).thenReturn(Collections.emptyList());
        lenient().when(blackboard.lastResult()).thenReturn(null);
        lenient().when(processOptions.getPlannerType()).thenReturn(PlannerType.GOAP);

        return process;
    }

    private static void mockUsageAndCost(AgentProcess process, Usage usage, double cost) {
        lenient().when(process.usage()).thenReturn(usage != null ? usage : new Usage(null, null, null));
        lenient().when(process.cost()).thenReturn(cost);
    }

    @SuppressWarnings("unchecked")
    private static LlmRequestEvent<?> createMockLlmRequestEvent(AgentProcess process, String modelName) {
        var event = mock(LlmRequestEvent.class);
        var metadata = mock(LlmMetadata.class);
        lenient().when(metadata.getName()).thenReturn(modelName);
        lenient().when(event.getAgentProcess()).thenReturn(process);
        lenient().when(event.getLlmMetadata()).thenReturn(metadata);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static LlmResponseEvent<?> createMockLlmResponseEvent(AgentProcess process, String modelName, Duration runningTime) {
        var event = mock(LlmResponseEvent.class);
        var request = createMockLlmRequestEvent(process, modelName);
        lenient().when(event.getRequest()).thenReturn(request);
        lenient().when(event.getRunningTime()).thenReturn(runningTime);
        lenient().when(event.getAgentProcess()).thenReturn(process);
        return event;
    }

    private static ToolCallRequestEvent createMockToolCallRequestEvent(AgentProcess process, String toolName) {
        var event = mock(ToolCallRequestEvent.class);
        lenient().when(event.getTool()).thenReturn(toolName);
        lenient().when(event.getAgentProcess()).thenReturn(process);
        return event;
    }

    private static ToolLoopCompletedEvent createMockToolLoopCompletedEvent(AgentProcess process, int totalIterations) {
        var event = mock(ToolLoopCompletedEvent.class);
        lenient().when(event.getTotalIterations()).thenReturn(totalIterations);
        lenient().when(event.getAgentProcess()).thenReturn(process);
        return event;
    }

    private static ToolCallResponseEvent createToolCallResponseEvent(AgentProcess process, String toolName) {
        ToolCallRequestEvent request = mock(ToolCallRequestEvent.class);
        lenient().when(request.getTool()).thenReturn(toolName);

        ToolCallResponseEvent event = mock(ToolCallResponseEvent.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            if (methodName.equals("getAgentProcess")) {
                return process;
            }
            if (methodName.equals("getRequest")) {
                return request;
            }
            if (methodName.equals("getRunningTime")) {
                return Duration.ofMillis(50);
            }
            return null;
        });

        return event;
    }
}
