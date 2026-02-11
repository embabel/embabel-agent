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
import com.embabel.agent.core.*;
import com.embabel.agent.observability.ObservabilityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

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

            var toolResponseEvent = createToolCallResponseEvent(
                    process, "WebSearch", null, new RuntimeException("search failed"));
            listener.onProcessEvent(toolResponseEvent);

            Counter counter = registry.find("embabel.tool.errors.total").tag("tool", "WebSearch").counter();
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

            var toolResponseEvent = createToolCallResponseEvent(
                    process, "WebSearch", "result", null);
            listener.onProcessEvent(toolResponseEvent);

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

    private static ToolCallResponseEvent createToolCallResponseEvent(AgentProcess process, String toolName,
                                                                      String successResult, Throwable error) {
        ToolCallRequestEvent request = mock(ToolCallRequestEvent.class);
        lenient().when(request.getTool()).thenReturn(toolName);

        MockResult mockResult = new MockResult(successResult, error);

        ToolCallResponseEvent event = mock(ToolCallResponseEvent.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            if (methodName.equals("getResult") || methodName.startsWith("getResult-")) {
                return mockResult;
            }
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

    /**
     * A mock Result class that mimics Kotlin's Result&lt;T&gt;.
     */
    static class MockResult {
        private final Object successValue;
        private final Throwable error;

        MockResult(Object successValue, Throwable error) {
            this.successValue = successValue;
            this.error = error;
        }

        public Object getOrNull() {
            return error == null ? successValue : null;
        }

        public Throwable exceptionOrNull() {
            return error;
        }
    }
}
