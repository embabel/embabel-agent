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
    class GetActionOutputs {

        @Test
        @DisplayName("should resolve the action's declared output binding from the blackboard")
        void resolvesDeclaredOutput() {
            var action = mock(Action.class);
            var process = mock(AgentProcess.class);
            var blackboard = mock(Blackboard.class);
            var agent = mock(Agent.class);
            var binding = mock(IoBinding.class);

            when(action.getOutputs()).thenReturn(Set.of(binding));
            when(binding.getValue()).thenReturn("it:java.lang.String");
            when(process.getBlackboard()).thenReturn(blackboard);
            when(process.getAgent()).thenReturn(agent);
            when(blackboard.getValue("it", "java.lang.String", agent)).thenReturn("action output");

            assertThat(ObservationUtils.getActionOutputs(action, process))
                    .isEqualTo("it (java.lang.String): action output");
        }

        @Test
        @DisplayName("should return empty string for empty outputs")
        void emptyOutputs() {
            var action = mock(Action.class);
            var process = mock(AgentProcess.class);
            when(action.getOutputs()).thenReturn(Collections.emptySet());

            assertThat(ObservationUtils.getActionOutputs(action, process)).isEmpty();
        }
    }
}
