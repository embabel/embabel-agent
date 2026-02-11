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
package com.embabel.agent.observability.mdc;

import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.event.*;
import com.embabel.agent.core.*;
import com.embabel.agent.observability.ObservabilityProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link MdcPropagationEventListener}.
 *
 * Covers:
 * - Agent creation sets MDC keys (run_id, agent.name)
 * - Action start sets MDC key (action.name)
 * - Action result clears action.name from MDC
 * - Agent completed/failed/killed clears all MDC keys
 * - MDC propagation disabled → MDC not modified
 */
@ExtendWith(MockitoExtension.class)
class MdcPropagationEventListenerTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ================================================================================
    // AGENT CREATION TESTS
    // ================================================================================

    @Nested
    @DisplayName("Agent Creation Tests")
    class AgentCreationTests {

        @Test
        @DisplayName("Agent creation should set run_id and agent.name in MDC")
        void agentCreation_shouldSetMdcKeys() {
            ObservabilityProperties properties = new ObservabilityProperties();
            MdcPropagationEventListener listener = new MdcPropagationEventListener(properties);

            AgentProcess process = createMockAgentProcess("run-123", "CustomerServiceAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));

            assertThat(MDC.get("embabel.agent.run_id")).isEqualTo("run-123");
            assertThat(MDC.get("embabel.agent.name")).isEqualTo("CustomerServiceAgent");
        }
    }

    // ================================================================================
    // ACTION EXECUTION TESTS
    // ================================================================================

    @Nested
    @DisplayName("Action Execution Tests")
    class ActionExecutionTests {

        @Test
        @DisplayName("Action start should set action.name in MDC")
        void actionStart_shouldSetActionName() {
            ObservabilityProperties properties = new ObservabilityProperties();
            MdcPropagationEventListener listener = new MdcPropagationEventListener(properties);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "AnalyzeRequest");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);

            assertThat(MDC.get("embabel.action.name")).isEqualTo("AnalyzeRequest");
            // Agent keys should still be present
            assertThat(MDC.get("embabel.agent.run_id")).isEqualTo("run-1");
            assertThat(MDC.get("embabel.agent.name")).isEqualTo("TestAgent");
        }

        @Test
        @DisplayName("Action result should clear action.name from MDC")
        void actionResult_shouldClearActionName() {
            ObservabilityProperties properties = new ObservabilityProperties();
            MdcPropagationEventListener listener = new MdcPropagationEventListener(properties);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "AnalyzeRequest");
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "AnalyzeRequest");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);
            listener.onProcessEvent(actionResult);

            assertThat(MDC.get("embabel.action.name")).isNull();
            // Agent keys should still be present
            assertThat(MDC.get("embabel.agent.run_id")).isEqualTo("run-1");
            assertThat(MDC.get("embabel.agent.name")).isEqualTo("TestAgent");
        }
    }

    // ================================================================================
    // AGENT COMPLETION TESTS
    // ================================================================================

    @Nested
    @DisplayName("Agent Completion Tests")
    class AgentCompletionTests {

        @Test
        @DisplayName("Agent completed should clear all MDC keys")
        void agentCompleted_shouldClearAllMdcKeys() {
            ObservabilityProperties properties = new ObservabilityProperties();
            MdcPropagationEventListener listener = new MdcPropagationEventListener(properties);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            assertThat(MDC.get("embabel.agent.run_id")).isNotNull();

            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            assertThat(MDC.get("embabel.agent.run_id")).isNull();
            assertThat(MDC.get("embabel.agent.name")).isNull();
            assertThat(MDC.get("embabel.action.name")).isNull();
        }

        @Test
        @DisplayName("Agent failed should clear all MDC keys")
        void agentFailed_shouldClearAllMdcKeys() {
            ObservabilityProperties properties = new ObservabilityProperties();
            MdcPropagationEventListener listener = new MdcPropagationEventListener(properties);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            lenient().when(process.getFailureInfo()).thenReturn("error");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessFailedEvent(process));

            assertThat(MDC.get("embabel.agent.run_id")).isNull();
            assertThat(MDC.get("embabel.agent.name")).isNull();
            assertThat(MDC.get("embabel.action.name")).isNull();
        }

        @Test
        @DisplayName("Process killed should clear all MDC keys")
        void processKilled_shouldClearAllMdcKeys() {
            ObservabilityProperties properties = new ObservabilityProperties();
            MdcPropagationEventListener listener = new MdcPropagationEventListener(properties);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new ProcessKilledEvent(process));

            assertThat(MDC.get("embabel.agent.run_id")).isNull();
            assertThat(MDC.get("embabel.agent.name")).isNull();
            assertThat(MDC.get("embabel.action.name")).isNull();
        }
    }

    // ================================================================================
    // DISABLED TESTS
    // ================================================================================

    @Nested
    @DisplayName("MDC Propagation Disabled Tests")
    class DisabledTests {

        @Test
        @DisplayName("MDC should not be modified when mdcPropagation is disabled")
        void mdcNotModified_whenDisabled() {
            ObservabilityProperties properties = new ObservabilityProperties();
            properties.setMdcPropagation(false);
            MdcPropagationEventListener listener = new MdcPropagationEventListener(properties);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "MyAction");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);

            assertThat(MDC.get("embabel.agent.run_id")).isNull();
            assertThat(MDC.get("embabel.agent.name")).isNull();
            assertThat(MDC.get("embabel.action.name")).isNull();
        }
    }

    // ================================================================================
    // FULL LIFECYCLE TEST
    // ================================================================================

    @Nested
    @DisplayName("Full Lifecycle Tests")
    class FullLifecycleTests {

        @Test
        @DisplayName("Full lifecycle should correctly set and clear MDC keys")
        void fullLifecycle_shouldSetAndClearMdcCorrectly() {
            ObservabilityProperties properties = new ObservabilityProperties();
            MdcPropagationEventListener listener = new MdcPropagationEventListener(properties);

            AgentProcess process = createMockAgentProcess("run-42", "OrderAgent");

            // Agent creation
            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            assertThat(MDC.get("embabel.agent.run_id")).isEqualTo("run-42");
            assertThat(MDC.get("embabel.agent.name")).isEqualTo("OrderAgent");

            // Action start
            ActionExecutionStartEvent action1Start = createMockActionStartEvent(process, "ValidateOrder");
            listener.onProcessEvent(action1Start);
            assertThat(MDC.get("embabel.action.name")).isEqualTo("ValidateOrder");

            // Action end
            ActionExecutionResultEvent action1Result = createMockActionResultEvent(process, "ValidateOrder");
            listener.onProcessEvent(action1Result);
            assertThat(MDC.get("embabel.action.name")).isNull();

            // Second action
            ActionExecutionStartEvent action2Start = createMockActionStartEvent(process, "ProcessPayment");
            listener.onProcessEvent(action2Start);
            assertThat(MDC.get("embabel.action.name")).isEqualTo("ProcessPayment");

            // Second action end
            ActionExecutionResultEvent action2Result = createMockActionResultEvent(process, "ProcessPayment");
            listener.onProcessEvent(action2Result);
            assertThat(MDC.get("embabel.action.name")).isNull();

            // Agent completed - all cleared
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));
            assertThat(MDC.get("embabel.agent.run_id")).isNull();
            assertThat(MDC.get("embabel.agent.name")).isNull();
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

    private static ActionExecutionStartEvent createMockActionStartEvent(AgentProcess process, String actionName) {
        ActionExecutionStartEvent event = mock(ActionExecutionStartEvent.class);
        lenient().when(event.getAgentProcess()).thenReturn(process);

        Action action = mock(Action.class);
        lenient().when(action.getName()).thenReturn(actionName);
        lenient().doReturn(action).when(event).getAction();

        return event;
    }

    private static ActionExecutionResultEvent createMockActionResultEvent(AgentProcess process, String actionName) {
        ActionExecutionResultEvent event = mock(ActionExecutionResultEvent.class);
        lenient().when(event.getAgentProcess()).thenReturn(process);
        lenient().when(event.getRunningTime()).thenReturn(Duration.ofMillis(100));

        Action action = mock(Action.class);
        lenient().when(action.getName()).thenReturn(actionName);
        lenient().doReturn(action).when(event).getAction();

        ActionStatus actionStatus = mock(ActionStatus.class);
        ActionStatusCode statusCode = mock(ActionStatusCode.class);
        lenient().when(statusCode.name()).thenReturn("SUCCESS");
        lenient().when(actionStatus.getStatus()).thenReturn(statusCode);
        lenient().doReturn(actionStatus).when(event).getActionStatus();

        return event;
    }
}
