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
package com.embabel.agent.observability.observation;

import com.embabel.agent.core.*;
import com.embabel.plan.Plan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ObservationUtilsTest {

    @Nested
    class Truncate {

        @Test
        @DisplayName("should return empty string for null")
        void nullValue() {
            assertThat(ObservationUtils.truncate(null, 100)).isEmpty();
        }

        @Test
        @DisplayName("should return value unchanged when within limit")
        void withinLimit() {
            assertThat(ObservationUtils.truncate("short", 100)).isEqualTo("short");
        }

        @Test
        @DisplayName("should truncate and add ellipsis when over limit")
        void overLimit() {
            assertThat(ObservationUtils.truncate("abcdefghij", 5)).isEqualTo("abcde...");
        }

        @Test
        @DisplayName("should return value unchanged when exactly at limit")
        void exactlyAtLimit() {
            assertThat(ObservationUtils.truncate("abcde", 5)).isEqualTo("abcde");
        }
    }

    @Nested
    class ExtractGoalName {

        @Test
        @DisplayName("should return goal name from process goal")
        void fromProcessGoal() {
            var process = mock(AgentProcess.class);
            var goal = mock(Goal.class);
            lenient().when(process.getGoal()).thenReturn(goal);
            when(goal.getName()).thenReturn("MyGoal");

            assertThat(ObservationUtils.extractGoalName(process)).isEqualTo("MyGoal");
        }

        @Test
        @DisplayName("should fall back to agent goals when process goal is null")
        void fromAgentGoals() {
            var process = mock(AgentProcess.class);
            var agent = mock(Agent.class);
            var goal = mock(Goal.class);
            when(process.getGoal()).thenReturn(null);
            when(process.getAgent()).thenReturn(agent);
            when(agent.getGoals()).thenReturn(Set.of(goal));
            when(goal.getName()).thenReturn("AgentGoal");

            assertThat(ObservationUtils.extractGoalName(process)).isEqualTo("AgentGoal");
        }

        @Test
        @DisplayName("should return unknown when no goals")
        void noGoals() {
            var process = mock(AgentProcess.class);
            var agent = mock(Agent.class);
            when(process.getGoal()).thenReturn(null);
            when(process.getAgent()).thenReturn(agent);
            when(agent.getGoals()).thenReturn(Collections.emptySet());

            assertThat(ObservationUtils.extractGoalName(process)).isEqualTo("unknown");
        }
    }

    @Nested
    class GetBlackboardSnapshot {

        @Test
        @DisplayName("should return empty string for empty blackboard")
        void emptyBlackboard() {
            var process = mock(AgentProcess.class);
            var blackboard = mock(Blackboard.class);
            when(process.getBlackboard()).thenReturn(blackboard);
            when(blackboard.getObjects()).thenReturn(Collections.emptyList());

            assertThat(ObservationUtils.getBlackboardSnapshot(process)).isEmpty();
        }

        @Test
        @DisplayName("should return empty string for null objects")
        void nullObjects() {
            var process = mock(AgentProcess.class);
            var blackboard = mock(Blackboard.class);
            when(process.getBlackboard()).thenReturn(blackboard);
            when(blackboard.getObjects()).thenReturn(null);

            assertThat(ObservationUtils.getBlackboardSnapshot(process)).isEmpty();
        }

        @Test
        @DisplayName("should format single object")
        void singleObject() {
            var process = mock(AgentProcess.class);
            var blackboard = mock(Blackboard.class);
            when(process.getBlackboard()).thenReturn(blackboard);
            when(blackboard.getObjects()).thenReturn(List.of("hello"));

            var result = ObservationUtils.getBlackboardSnapshot(process);
            assertThat(result).isEqualTo("String: hello");
        }

        @Test
        @DisplayName("should separate multiple objects with ---")
        void multipleObjects() {
            var process = mock(AgentProcess.class);
            var blackboard = mock(Blackboard.class);
            when(process.getBlackboard()).thenReturn(blackboard);
            when(blackboard.getObjects()).thenReturn(List.of("a", "b"));

            var result = ObservationUtils.getBlackboardSnapshot(process);
            assertThat(result).isEqualTo("String: a\n---\nString: b");
        }
    }

    @Nested
    class GetActionInputs {

        @Test
        @DisplayName("should return empty string for null inputs")
        void nullInputs() {
            var action = mock(Action.class);
            var process = mock(AgentProcess.class);
            when(action.getInputs()).thenReturn(null);

            assertThat(ObservationUtils.getActionInputs(action, process)).isEmpty();
        }

        @Test
        @DisplayName("should return empty string for empty inputs")
        void emptyInputs() {
            var action = mock(Action.class);
            var process = mock(AgentProcess.class);
            when(action.getInputs()).thenReturn(Collections.emptySet());

            assertThat(ObservationUtils.getActionInputs(action, process)).isEmpty();
        }

        @Test
        @DisplayName("should resolve binding with name:type format")
        void namedBinding() {
            var action = mock(Action.class);
            var process = mock(AgentProcess.class);
            var blackboard = mock(Blackboard.class);
            var agent = mock(Agent.class);
            var binding = mock(IoBinding.class);

            when(action.getInputs()).thenReturn(Set.of(binding));
            when(binding.getValue()).thenReturn("myVar:java.lang.String");
            when(process.getBlackboard()).thenReturn(blackboard);
            when(process.getAgent()).thenReturn(agent);
            when(blackboard.getValue("myVar", "java.lang.String", agent)).thenReturn("value1");

            var result = ObservationUtils.getActionInputs(action, process);
            assertThat(result).isEqualTo("myVar (java.lang.String): value1");
        }

        @Test
        @DisplayName("should use default binding name for type-only format")
        void defaultBinding() {
            var action = mock(Action.class);
            var process = mock(AgentProcess.class);
            var blackboard = mock(Blackboard.class);
            var agent = mock(Agent.class);
            var binding = mock(IoBinding.class);

            when(action.getInputs()).thenReturn(Set.of(binding));
            when(binding.getValue()).thenReturn("java.lang.String");
            when(process.getBlackboard()).thenReturn(blackboard);
            when(process.getAgent()).thenReturn(agent);
            when(blackboard.getValue("it", "java.lang.String", agent)).thenReturn("defaultVal");

            var result = ObservationUtils.getActionInputs(action, process);
            assertThat(result).isEqualTo("it (java.lang.String): defaultVal");
        }

        @Test
        @DisplayName("should skip null values from blackboard")
        void nullValue() {
            var action = mock(Action.class);
            var process = mock(AgentProcess.class);
            var blackboard = mock(Blackboard.class);
            var agent = mock(Agent.class);
            var binding = mock(IoBinding.class);

            when(action.getInputs()).thenReturn(Set.of(binding));
            when(binding.getValue()).thenReturn("x:SomeType");
            when(process.getBlackboard()).thenReturn(blackboard);
            when(process.getAgent()).thenReturn(agent);
            when(blackboard.getValue("x", "SomeType", agent)).thenReturn(null);

            assertThat(ObservationUtils.getActionInputs(action, process)).isEmpty();
        }
    }

    @Nested
    class FormatPlanSteps {

        @Test
        @DisplayName("should return [] for null plan")
        void nullPlan() {
            assertThat(ObservationUtils.formatPlanSteps(null)).isEqualTo("[]");
        }

        @Test
        @DisplayName("should return [] for empty actions")
        void emptyActions() {
            var plan = mock(Plan.class);
            when(plan.getActions()).thenReturn(Collections.emptyList());

            assertThat(ObservationUtils.formatPlanSteps(plan)).isEqualTo("[]");
        }

        @Test
        @DisplayName("should format numbered action list")
        void withActions() {
            var plan = mock(Plan.class);
            var action1 = mock(com.embabel.plan.Action.class);
            var action2 = mock(com.embabel.plan.Action.class);
            when(action1.getName()).thenReturn("Step1");
            when(action2.getName()).thenReturn("Step2");
            when(plan.getActions()).thenReturn(List.of(action1, action2));
            when(plan.getGoal()).thenReturn(null);

            assertThat(ObservationUtils.formatPlanSteps(plan)).isEqualTo("1. Step1\n2. Step2");
        }

        @Test
        @DisplayName("should append goal when present")
        void withGoal() {
            var plan = mock(Plan.class);
            var action1 = mock(com.embabel.plan.Action.class);
            var goal = mock(Goal.class);
            when(action1.getName()).thenReturn("Step1");
            when(plan.getActions()).thenReturn(List.of(action1));
            when(plan.getGoal()).thenReturn(goal);
            when(goal.getName()).thenReturn("FinalGoal");

            assertThat(ObservationUtils.formatPlanSteps(plan)).isEqualTo("1. Step1\n-> Goal: FinalGoal");
        }
    }
}
