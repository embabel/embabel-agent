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

import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.event.LlmRequestEvent;
import com.embabel.agent.api.event.ToolLoopStartEvent;
import com.embabel.agent.api.event.observation.ActionObservationContext;
import com.embabel.agent.api.event.observation.AgentObservationContext;
import com.embabel.agent.api.event.observation.LlmObservationContext;
import com.embabel.agent.api.event.observation.ToolLoopObservationContext;
import com.embabel.agent.api.identity.User;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.AgentProcessStatusCode;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.chat.Conversation;
import com.embabel.common.ai.model.LlmMetadata;
import com.embabel.plan.Action;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.GlobalObservationConvention;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the four core observation conventions. Verifies that each global
 * convention is selected by context type and produces the expected key values, read
 * at stop time. Uses a real {@link ObservationRegistry} with a capturing handler; no
 * tracer / OTel bridge is required at this level (nesting is proven separately with a
 * real bridge).
 */
class EmbabelObservationConventionsTest {

    /**
     * Observe the given context under the given convention and return the merged
     * low+high cardinality key values as a map, plus the context for error inspection.
     */
    private static Observation.Context observe(
            GlobalObservationConvention<? extends Observation.Context> convention,
            Observation.Context context,
            String name) {
        ObservationRegistry registry = ObservationRegistry.create();
        // A handler that supports every context, so observations actually run (not no-op).
        registry.observationConfig().observationHandler(c -> true);
        registry.observationConfig().observationConvention(convention);
        Observation.createNotStarted(name, () -> context, registry)
                .observe((Supplier<Object>) () -> null);
        return context;
    }

    private static Map<String, String> allKeyValues(Observation.Context context) {
        return KeyValues.of(context.getLowCardinalityKeyValues())
                .and(context.getHighCardinalityKeyValues())
                .stream()
                .collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
    }

    @Nested
    @DisplayName("agent convention")
    class AgentConvention {

        private AgentProcess process(AgentProcessStatusCode status, String parentId,
                                     List<Conversation> conversations, List<User> users) {
            AgentProcess process = mock(AgentProcess.class, RETURNS_DEEP_STUBS);
            lenient().when(process.getAgent().getName()).thenReturn("TestAgent");
            lenient().when(process.getId()).thenReturn("run-1");
            lenient().when(process.getParentId()).thenReturn(parentId);
            lenient().when(process.getProcessOptions().getPlannerType()).thenReturn(PlannerType.GOAP);
            lenient().when(process.getStatus()).thenReturn(status);
            lenient().when(process.getGoal()).thenReturn(null);
            lenient().when(process.objectsOfType(Conversation.class)).thenReturn(conversations);
            lenient().when(process.objectsOfType(User.class)).thenReturn(users);
            lenient().when(process.lastResult()).thenReturn(null);
            return process;
        }

        @Test
        @DisplayName("records identity, planner and final status")
        void recordsCoreAttributes() {
            AgentProcess process = process(AgentProcessStatusCode.COMPLETED, null,
                    List.of(), List.of());
            AgentObservationContext ctx = new AgentObservationContext(process);

            Map<String, String> kv = allKeyValues(
                    observe(new EmbabelAgentObservationConvention(4000), ctx, "embabel.agent"));

            assertEquals("agent", kv.get("gen_ai.operation.name"));
            assertEquals("TestAgent", kv.get("embabel.agent.name"));
            assertEquals("false", kv.get("embabel.agent.is_subagent"));
            assertEquals("GOAP", kv.get("embabel.agent.planner_type"));
            assertEquals("COMPLETED", kv.get("embabel.agent.status"));
            assertEquals("run-1", kv.get("embabel.run.id"));
        }

        @Test
        @DisplayName("STUCK is recorded as status, not as an error (ChatBot resting state)")
        void stuckIsNotAnError() {
            AgentProcess process = process(AgentProcessStatusCode.STUCK, null,
                    List.of(), List.of());
            AgentObservationContext ctx = new AgentObservationContext(process);

            Observation.Context result = observe(
                    new EmbabelAgentObservationConvention(4000), ctx, "embabel.agent");

            assertEquals("STUCK", allKeyValues(result).get("embabel.agent.status"));
            // The work did not throw, so no error is attached: the span closes OK.
            assertNull(result.getError());
        }

        @Test
        @DisplayName("conversation.id is the last Conversation id, user.id the last User id")
        void resolvesSessionAndUserFromBlackboard() {
            Conversation conversation = mock(Conversation.class);
            when(conversation.getId()).thenReturn("conv-9");
            User user = mock(User.class);
            when(user.getId()).thenReturn("user-7");

            AgentProcess process = process(AgentProcessStatusCode.RUNNING, "parent-2",
                    List.of(conversation), List.of(user));
            AgentObservationContext ctx = new AgentObservationContext(process);

            Map<String, String> kv = allKeyValues(
                    observe(new EmbabelAgentObservationConvention(4000), ctx, "embabel.agent"));

            assertEquals("conv-9", kv.get("gen_ai.conversation.id"));
            assertEquals("user-7", kv.get("user.id"));
            assertEquals("true", kv.get("embabel.agent.is_subagent"));
            assertEquals("parent-2", kv.get("embabel.parent.id"));
        }

        @Test
        @DisplayName("conversation.id falls back to run id when no Conversation on the blackboard")
        void conversationIdFallsBackToRunId() {
            AgentProcess process = process(AgentProcessStatusCode.RUNNING, null,
                    List.of(), List.of());
            AgentObservationContext ctx = new AgentObservationContext(process);

            Map<String, String> kv = allKeyValues(
                    observe(new EmbabelAgentObservationConvention(4000), ctx, "embabel.agent"));

            assertEquals("run-1", kv.get("gen_ai.conversation.id"));
            assertFalse(kv.containsKey("user.id"));
        }

        @Test
        @DisplayName("records agent.result from blackboard lastResult, absent when null")
        void recordsAgentResult() {
            AgentProcess process = process(AgentProcessStatusCode.COMPLETED, null,
                    List.of(), List.of());
            when(process.lastResult()).thenReturn("the final answer");
            AgentObservationContext ctx = new AgentObservationContext(process);

            Map<String, String> kv = allKeyValues(
                    observe(new EmbabelAgentObservationConvention(4000), ctx, "embabel.agent"));

            assertEquals("the final answer", kv.get("embabel.agent.result"));
        }
    }

    @Nested
    @DisplayName("action convention")
    class ActionConvention {

        @Test
        @DisplayName("records action and agent name")
        void recordsActionAttributes() {
            AgentProcess process = mock(AgentProcess.class, RETURNS_DEEP_STUBS);
            lenient().when(process.getAgent().getName()).thenReturn("TestAgent");
            lenient().when(process.getId()).thenReturn("run-1");
            lenient().when(process.lastResult()).thenReturn(null);
            Action action = mock(Action.class);
            when(action.getName()).thenReturn("MyAction");

            ActionObservationContext ctx = new ActionObservationContext(process, action);
            Map<String, String> kv = allKeyValues(
                    observe(new EmbabelActionObservationConvention(4000), ctx, "embabel.action"));

            assertEquals("action", kv.get("gen_ai.operation.name"));
            assertEquals("MyAction", kv.get("embabel.action.name"));
            assertEquals("MyAction", kv.get("embabel.action.short_name"));
            assertEquals("TestAgent", kv.get("embabel.agent.name"));
            assertEquals("run-1", kv.get("embabel.run.id"));
            assertFalse(kv.containsKey("embabel.action.result"));
        }

        @Test
        @DisplayName("fully-qualified action name kept as-is; short_name carries the method name")
        void addsShortNameForFullyQualifiedName() {
            AgentProcess process = mock(AgentProcess.class, RETURNS_DEEP_STUBS);
            lenient().when(process.getAgent().getName()).thenReturn("TestAgent");
            lenient().when(process.getId()).thenReturn("run-1");
            lenient().when(process.lastResult()).thenReturn(null);
            Action action = mock(Action.class);
            String fqn = "com.example.agent.TimeCalculatorAgent.calculateHours";
            when(action.getName()).thenReturn(fqn);

            ActionObservationContext ctx = new ActionObservationContext(process, action);
            Map<String, String> kv = allKeyValues(
                    observe(new EmbabelActionObservationConvention(4000), ctx, "embabel.action"));

            assertEquals(fqn, kv.get("embabel.action.name"));
            assertEquals("calculateHours", kv.get("embabel.action.short_name"));
        }

        @Test
        @DisplayName("records action.result from blackboard lastResult")
        void recordsActionResult() {
            AgentProcess process = mock(AgentProcess.class, RETURNS_DEEP_STUBS);
            lenient().when(process.getAgent().getName()).thenReturn("TestAgent");
            lenient().when(process.getId()).thenReturn("run-1");
            when(process.lastResult()).thenReturn("action output");
            Action action = mock(Action.class);
            when(action.getName()).thenReturn("MyAction");

            ActionObservationContext ctx = new ActionObservationContext(process, action);
            Map<String, String> kv = allKeyValues(
                    observe(new EmbabelActionObservationConvention(4000), ctx, "embabel.action"));

            assertEquals("action output", kv.get("embabel.action.result"));
        }
    }

    @Nested
    @DisplayName("tool loop convention")
    class ToolLoopConvention {

        @Test
        @DisplayName("records interaction, tools and max iterations")
        void recordsToolLoopAttributes() {
            ToolLoopStartEvent event = mock(ToolLoopStartEvent.class, RETURNS_DEEP_STUBS);
            lenient().when(event.getAgentProcess().getAgent().getName()).thenReturn("TestAgent");
            lenient().when(event.getAgentProcess().getId()).thenReturn("run-1");
            lenient().when(event.getAction()).thenReturn(null);
            lenient().when(event.getInteractionId()).thenReturn("interaction-3");
            lenient().when(event.getToolNames()).thenReturn(List.of("toolA", "toolB"));
            lenient().when(event.getMaxIterations()).thenReturn(20);
            lenient().when(event.getOutputClass()).thenReturn((Class) String.class);

            ToolLoopObservationContext ctx = new ToolLoopObservationContext(event);
            Map<String, String> kv = allKeyValues(
                    observe(new EmbabelToolLoopObservationConvention(), ctx, "embabel.tool_loop"));

            assertEquals("tool_loop", kv.get("gen_ai.operation.name"));
            assertEquals("20", kv.get("embabel.tool_loop.max_iterations"));
            assertEquals("interaction-3", kv.get("embabel.interaction.id"));
            assertEquals("toolA,toolB", kv.get("embabel.tool_loop.tool_names"));
            assertEquals(String.class.getName(), kv.get("embabel.tool_loop.output_class"));
        }
    }

    @Nested
    @DisplayName("llm convention")
    class LlmConvention {

        @Test
        @DisplayName("records model and interaction")
        void recordsModelAttributes() {
            LlmRequestEvent<?> event = mock(LlmRequestEvent.class, RETURNS_DEEP_STUBS);
            LlmMetadata metadata = LlmMetadata.create("gpt-4o", "openai");
            lenient().when(event.getLlmMetadata()).thenReturn(metadata);
            lenient().when(event.getAgentProcess().getAgent().getName()).thenReturn("TestAgent");
            lenient().when(event.getAgentProcess().getId()).thenReturn("run-1");
            lenient().when(event.getAction()).thenReturn(null);
            lenient().when(event.getInteraction().getId()).thenReturn("interaction-3");

            LlmObservationContext ctx = new LlmObservationContext(event);
            Map<String, String> kv = allKeyValues(
                    observe(new EmbabelLlmObservationConvention(), ctx, "embabel.llm"));

            assertEquals("chat", kv.get("gen_ai.operation.name"));
            assertEquals("gpt-4o", kv.get("gen_ai.request.model"));
            assertEquals("TestAgent", kv.get("embabel.agent.name"));
            assertEquals("interaction-3", kv.get("embabel.interaction.id"));
        }
    }
}
