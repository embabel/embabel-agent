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

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.observation.Observation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmbabelTracingObservationHandler using Mockito mocks.
 * Validates parent-child span resolution for all event types: LLM_CALL, TOOL_LOOP,
 * AGENT_PROCESS (sub-agent). Tests cross-thread parent resolution via parentObservation
 * and parallel LLM support via tracer.currentSpan().
 */
@ExtendWith(MockitoExtension.class)
class EmbabelTracingObservationHandlerTest {

    @Mock
    private Tracer tracer;

    private EmbabelTracingObservationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new EmbabelTracingObservationHandler(tracer);
    }

    // Verifies LLM spans resolve parent to Action -> Agent fallback chain
    @Nested
    @DisplayName("LLM_CALL parent resolution")
    class LlmCallParentResolution {

        @Test
        @DisplayName("LLM span should use Action span as parent when Action is active")
        void llmSpan_shouldUseActionAsParent() {
            // Setup: create spans for agent and action
            Span agentSpan = createMockSpan("agent-span");
            Span actionSpan = createMockSpan("action-span");
            Span llmSpan = createMockSpan("llm-span");

            Tracer.SpanInScope agentScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope actionScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope llmScope = mock(Tracer.SpanInScope.class);

            // Agent start: root span
            when(tracer.nextSpan()).thenReturn(agentSpan);
            when(agentSpan.name(anyString())).thenReturn(agentSpan);
            when(agentSpan.start()).thenReturn(agentSpan);
            when(tracer.withSpan(null)).thenReturn(agentScope);

            EmbabelObservationContext agentCtx = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
            handler.onStart(agentCtx);
            // Simulate scope opening
            when(tracer.withSpan(agentSpan)).thenReturn(agentScope);
            handler.onScopeOpened(agentCtx);

            // Action start: child of agent
            when(tracer.nextSpan(agentSpan)).thenReturn(actionSpan);
            when(actionSpan.name(anyString())).thenReturn(actionSpan);
            when(actionSpan.start()).thenReturn(actionSpan);

            EmbabelObservationContext actionCtx = EmbabelObservationContext.action("run-1", "MyAction");
            handler.onStart(actionCtx);
            when(tracer.withSpan(actionSpan)).thenReturn(actionScope);
            handler.onScopeOpened(actionCtx);

            // LLM call start: should use action as parent
            when(tracer.nextSpan(actionSpan)).thenReturn(llmSpan);
            when(llmSpan.name(anyString())).thenReturn(llmSpan);
            when(llmSpan.start()).thenReturn(llmSpan);
            when(llmSpan.tag(anyString(), anyString())).thenReturn(llmSpan);

            EmbabelObservationContext llmCtx = EmbabelObservationContext.llmCall("run-1", ObservationKeys.LLM_PREFIX + "gpt-4");
            handler.onStart(llmCtx);

            // Verify: LLM span was created with actionSpan as parent
            verify(tracer).nextSpan(actionSpan);
        }

        @Test
        @DisplayName("LLM span should fall back to Agent span when no Action is active")
        void llmSpan_shouldFallbackToAgentSpan() {
            Span agentSpan = createMockSpan("agent-span");
            Span llmSpan = createMockSpan("llm-span");

            Tracer.SpanInScope agentScope = mock(Tracer.SpanInScope.class);

            // Agent start: root span
            when(tracer.nextSpan()).thenReturn(agentSpan);
            when(agentSpan.name(anyString())).thenReturn(agentSpan);
            when(agentSpan.start()).thenReturn(agentSpan);
            when(tracer.withSpan(null)).thenReturn(agentScope);

            EmbabelObservationContext agentCtx = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
            handler.onStart(agentCtx);
            when(tracer.withSpan(agentSpan)).thenReturn(agentScope);
            handler.onScopeOpened(agentCtx);

            // LLM call start (no action): should use agent as parent
            when(tracer.nextSpan(agentSpan)).thenReturn(llmSpan);
            when(llmSpan.name(anyString())).thenReturn(llmSpan);
            when(llmSpan.start()).thenReturn(llmSpan);
            when(llmSpan.tag(anyString(), anyString())).thenReturn(llmSpan);

            EmbabelObservationContext llmCtx = EmbabelObservationContext.llmCall("run-1", ObservationKeys.LLM_PREFIX + "gpt-4");
            handler.onStart(llmCtx);

            // Verify: LLM span was created with agentSpan as parent
            verify(tracer).nextSpan(agentSpan);
        }

        @Test
        @DisplayName("LLM span should be untracked after onStop")
        void llmSpan_shouldBeUntrackedAfterStop() {
            Span agentSpan = createMockSpan("agent-span");
            Span actionSpan = createMockSpan("action-span");
            Span llmSpan1 = createMockSpan("llm-span-1");
            Span llmSpan2 = createMockSpan("llm-span-2");

            Tracer.SpanInScope agentScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope actionScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope llmScope = mock(Tracer.SpanInScope.class);

            // Agent start
            when(tracer.nextSpan()).thenReturn(agentSpan);
            when(agentSpan.name(anyString())).thenReturn(agentSpan);
            when(agentSpan.start()).thenReturn(agentSpan);
            when(tracer.withSpan(null)).thenReturn(agentScope);
            when(tracer.withSpan(agentSpan)).thenReturn(agentScope);

            EmbabelObservationContext agentCtx = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
            handler.onStart(agentCtx);
            handler.onScopeOpened(agentCtx);

            // Action start
            when(tracer.nextSpan(agentSpan)).thenReturn(actionSpan);
            when(actionSpan.name(anyString())).thenReturn(actionSpan);
            when(actionSpan.start()).thenReturn(actionSpan);
            when(tracer.withSpan(actionSpan)).thenReturn(actionScope);

            EmbabelObservationContext actionCtx = EmbabelObservationContext.action("run-1", "MyAction");
            handler.onStart(actionCtx);
            handler.onScopeOpened(actionCtx);

            // First LLM call - start and stop
            when(tracer.nextSpan(actionSpan)).thenReturn(llmSpan1);
            when(llmSpan1.name(anyString())).thenReturn(llmSpan1);
            when(llmSpan1.start()).thenReturn(llmSpan1);
            when(llmSpan1.tag(anyString(), anyString())).thenReturn(llmSpan1);
            when(tracer.withSpan(llmSpan1)).thenReturn(llmScope);

            EmbabelObservationContext llmCtx1 = EmbabelObservationContext.llmCall("run-1", ObservationKeys.LLM_PREFIX + "gpt-4");
            handler.onStart(llmCtx1);
            handler.onScopeOpened(llmCtx1);
            handler.onScopeClosed(llmCtx1);
            handler.onStop(llmCtx1);

            // Second LLM call - should still get action as parent (not stale llm span)
            when(tracer.nextSpan(actionSpan)).thenReturn(llmSpan2);
            when(llmSpan2.name(anyString())).thenReturn(llmSpan2);
            when(llmSpan2.start()).thenReturn(llmSpan2);
            when(llmSpan2.tag(anyString(), anyString())).thenReturn(llmSpan2);

            EmbabelObservationContext llmCtx2 = EmbabelObservationContext.llmCall("run-1", ObservationKeys.LLM_PREFIX + "gpt-4");
            handler.onStart(llmCtx2);

            // Verify: second LLM span also created with actionSpan as parent
            verify(tracer, times(2)).nextSpan(actionSpan);
        }
    }

    // Verifies tool-loop spans resolve parent to LLM (via currentSpan) -> Action -> Agent fallback chain
    @Nested
    @DisplayName("TOOL_LOOP parent resolution")
    class ToolLoopParentResolution {

        @Test
        @DisplayName("ToolLoop span should use LLM span as parent when LLM is active")
        void toolLoopSpan_shouldUseLlmAsParent() {
            Span agentSpan = createMockSpan("agent-span");
            Span actionSpan = createMockSpan("action-span");
            Span llmSpan = createMockSpan("llm-span");
            Span toolLoopSpan = createMockSpan("tool-loop-span");

            Tracer.SpanInScope agentScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope actionScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope llmScope = mock(Tracer.SpanInScope.class);

            // Agent start: root span
            when(tracer.nextSpan()).thenReturn(agentSpan);
            when(agentSpan.name(anyString())).thenReturn(agentSpan);
            when(agentSpan.start()).thenReturn(agentSpan);
            when(tracer.withSpan(null)).thenReturn(agentScope);

            var agentCtx = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
            handler.onStart(agentCtx);
            when(tracer.withSpan(agentSpan)).thenReturn(agentScope);
            handler.onScopeOpened(agentCtx);

            // Action start: child of agent
            when(tracer.nextSpan(agentSpan)).thenReturn(actionSpan);
            when(actionSpan.name(anyString())).thenReturn(actionSpan);
            when(actionSpan.start()).thenReturn(actionSpan);

            var actionCtx = EmbabelObservationContext.action("run-1", "MyAction");
            handler.onStart(actionCtx);
            when(tracer.withSpan(actionSpan)).thenReturn(actionScope);
            handler.onScopeOpened(actionCtx);

            // LLM call start: child of action
            when(tracer.nextSpan(actionSpan)).thenReturn(llmSpan);
            when(llmSpan.name(anyString())).thenReturn(llmSpan);
            when(llmSpan.start()).thenReturn(llmSpan);

            var llmCtx = EmbabelObservationContext.llmCall("run-1", ObservationKeys.LLM_PREFIX + "gpt-4");
            handler.onStart(llmCtx);
            when(tracer.withSpan(llmSpan)).thenReturn(llmScope);
            handler.onScopeOpened(llmCtx);

            // Tool loop start: should use LLM as parent (via tracer.currentSpan())
            when(tracer.currentSpan()).thenReturn(llmSpan);
            when(tracer.nextSpan(llmSpan)).thenReturn(toolLoopSpan);
            when(toolLoopSpan.name(anyString())).thenReturn(toolLoopSpan);
            when(toolLoopSpan.start()).thenReturn(toolLoopSpan);

            var toolLoopCtx = EmbabelObservationContext.toolLoop("run-1", ObservationKeys.toolLoopSpanName("interaction-1"));
            handler.onStart(toolLoopCtx);

            // Verify: ToolLoop span was created with llmSpan as parent (via currentSpan)
            verify(tracer).nextSpan(llmSpan);
        }

        @Test
        @DisplayName("ToolLoop span should fall back to Action span when no LLM is active")
        void toolLoopSpan_shouldFallbackToActionAsParent() {
            Span agentSpan = createMockSpan("agent-span");
            Span actionSpan = createMockSpan("action-span");
            Span toolLoopSpan = createMockSpan("tool-loop-span");

            Tracer.SpanInScope agentScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope actionScope = mock(Tracer.SpanInScope.class);

            // Agent start: root span
            when(tracer.nextSpan()).thenReturn(agentSpan);
            when(agentSpan.name(anyString())).thenReturn(agentSpan);
            when(agentSpan.start()).thenReturn(agentSpan);
            when(tracer.withSpan(null)).thenReturn(agentScope);

            var agentCtx = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
            handler.onStart(agentCtx);
            when(tracer.withSpan(agentSpan)).thenReturn(agentScope);
            handler.onScopeOpened(agentCtx);

            // Action start: child of agent
            when(tracer.nextSpan(agentSpan)).thenReturn(actionSpan);
            when(actionSpan.name(anyString())).thenReturn(actionSpan);
            when(actionSpan.start()).thenReturn(actionSpan);

            var actionCtx = EmbabelObservationContext.action("run-1", "MyAction");
            handler.onStart(actionCtx);
            when(tracer.withSpan(actionSpan)).thenReturn(actionScope);
            handler.onScopeOpened(actionCtx);

            // Tool loop start: should use action as parent
            when(tracer.nextSpan(actionSpan)).thenReturn(toolLoopSpan);
            when(toolLoopSpan.name(anyString())).thenReturn(toolLoopSpan);
            when(toolLoopSpan.start()).thenReturn(toolLoopSpan);
            when(toolLoopSpan.tag(anyString(), anyString())).thenReturn(toolLoopSpan);

            var toolLoopCtx = EmbabelObservationContext.toolLoop("run-1", ObservationKeys.toolLoopSpanName("interaction-1"));
            handler.onStart(toolLoopCtx);

            // Verify: ToolLoop span was created with actionSpan as parent
            verify(tracer, atLeastOnce()).nextSpan(actionSpan);
        }

        @Test
        @DisplayName("ToolLoop span should fall back to Agent span when no Action is active")
        void toolLoopSpan_shouldFallbackToAgentSpan() {
            Span agentSpan = createMockSpan("agent-span");
            Span toolLoopSpan = createMockSpan("tool-loop-span");

            Tracer.SpanInScope agentScope = mock(Tracer.SpanInScope.class);

            // Agent start: root span
            when(tracer.nextSpan()).thenReturn(agentSpan);
            when(agentSpan.name(anyString())).thenReturn(agentSpan);
            when(agentSpan.start()).thenReturn(agentSpan);
            when(tracer.withSpan(null)).thenReturn(agentScope);

            var agentCtx = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
            handler.onStart(agentCtx);
            when(tracer.withSpan(agentSpan)).thenReturn(agentScope);
            handler.onScopeOpened(agentCtx);

            // Tool loop start (no action): should use agent as parent
            when(tracer.nextSpan(agentSpan)).thenReturn(toolLoopSpan);
            when(toolLoopSpan.name(anyString())).thenReturn(toolLoopSpan);
            when(toolLoopSpan.start()).thenReturn(toolLoopSpan);
            when(toolLoopSpan.tag(anyString(), anyString())).thenReturn(toolLoopSpan);

            var toolLoopCtx = EmbabelObservationContext.toolLoop("run-1", ObservationKeys.toolLoopSpanName("interaction-1"));
            handler.onStart(toolLoopCtx);

            // Verify: ToolLoop span was created with agentSpan as parent
            verify(tracer).nextSpan(agentSpan);
        }

        @Test
        @DisplayName("ToolLoop should use parentObservation LLM span over currentSpan (cross-thread fix)")
        void toolLoopSpan_shouldPreferParentObservation_overCurrentSpan() {
            Span agentSpan = createMockSpan("agent-span");
            Span actionSpan = createMockSpan("action-span");
            Span llmSpan = createMockSpan("llm-span");
            Span toolLoopSpan = createMockSpan("tool-loop-span");

            Tracer.SpanInScope agentScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope actionScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope llmScope = mock(Tracer.SpanInScope.class);

            // Agent start: root span
            lenient().when(tracer.nextSpan()).thenReturn(agentSpan);
            lenient().when(agentSpan.name(anyString())).thenReturn(agentSpan);
            lenient().when(agentSpan.start()).thenReturn(agentSpan);
            lenient().when(tracer.withSpan(null)).thenReturn(agentScope);
            lenient().when(tracer.withSpan(agentSpan)).thenReturn(agentScope);

            var agentCtx = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
            handler.onStart(agentCtx);
            handler.onScopeOpened(agentCtx);

            // Action start: child of agent
            lenient().when(tracer.nextSpan(agentSpan)).thenReturn(actionSpan);
            lenient().when(actionSpan.name(anyString())).thenReturn(actionSpan);
            lenient().when(actionSpan.start()).thenReturn(actionSpan);
            lenient().when(tracer.withSpan(actionSpan)).thenReturn(actionScope);

            var actionCtx = EmbabelObservationContext.action("run-1", "MyAction");
            handler.onStart(actionCtx);
            handler.onScopeOpened(actionCtx);

            // LLM call start: child of action
            lenient().when(tracer.nextSpan(actionSpan)).thenReturn(llmSpan);
            lenient().when(llmSpan.name(anyString())).thenReturn(llmSpan);
            lenient().when(llmSpan.start()).thenReturn(llmSpan);
            lenient().when(tracer.withSpan(llmSpan)).thenReturn(llmScope);

            var llmCtx = EmbabelObservationContext.llmCall("run-1", ObservationKeys.LLM_PREFIX + "gpt-4");
            handler.onStart(llmCtx);
            handler.onScopeOpened(llmCtx);

            // Create mock LLM Observation wrapping llmCtx (which has TracingContext with llmSpan)
            Observation llmObservation = mock(Observation.class);
            lenient().when(llmObservation.getContext()).thenReturn(llmCtx);
            lenient().when(llmObservation.getContextView()).thenReturn(llmCtx);

            // Cross-thread: currentSpan() returns ACTION span (propagated via async thread)
            lenient().when(tracer.currentSpan()).thenReturn(actionSpan);

            // Stub for fix path: nextSpan(llmSpan) should return toolLoopSpan
            lenient().when(tracer.nextSpan(llmSpan)).thenReturn(toolLoopSpan);
            lenient().when(toolLoopSpan.name(anyString())).thenReturn(toolLoopSpan);
            lenient().when(toolLoopSpan.start()).thenReturn(toolLoopSpan);

            // Create tool-loop context with parentObservation pointing to LLM
            var toolLoopCtx = EmbabelObservationContext.toolLoop("run-1", ObservationKeys.toolLoopSpanName("interaction-1"));
            toolLoopCtx.setParentObservation(llmObservation);
            handler.onStart(toolLoopCtx);

            // Verify: ToolLoop span was created with llmSpan as parent (from parentObservation),
            // NOT actionSpan (from tracer.currentSpan())
            verify(tracer).nextSpan(llmSpan);
        }

        @Test
        @DisplayName("ToolLoop span should be untracked after onStop")
        void toolLoopSpan_shouldBeUntrackedAfterStop() {
            Span agentSpan = createMockSpan("agent-span");
            Span actionSpan = createMockSpan("action-span");
            Span toolLoopSpan1 = createMockSpan("tool-loop-span-1");
            Span toolLoopSpan2 = createMockSpan("tool-loop-span-2");

            Tracer.SpanInScope agentScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope actionScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope toolLoopScope = mock(Tracer.SpanInScope.class);

            // Agent start
            when(tracer.nextSpan()).thenReturn(agentSpan);
            when(agentSpan.name(anyString())).thenReturn(agentSpan);
            when(agentSpan.start()).thenReturn(agentSpan);
            when(tracer.withSpan(null)).thenReturn(agentScope);
            when(tracer.withSpan(agentSpan)).thenReturn(agentScope);

            var agentCtx = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
            handler.onStart(agentCtx);
            handler.onScopeOpened(agentCtx);

            // Action start
            when(tracer.nextSpan(agentSpan)).thenReturn(actionSpan);
            when(actionSpan.name(anyString())).thenReturn(actionSpan);
            when(actionSpan.start()).thenReturn(actionSpan);
            when(tracer.withSpan(actionSpan)).thenReturn(actionScope);

            var actionCtx = EmbabelObservationContext.action("run-1", "MyAction");
            handler.onStart(actionCtx);
            handler.onScopeOpened(actionCtx);

            // First tool loop - start and stop
            when(tracer.nextSpan(actionSpan)).thenReturn(toolLoopSpan1);
            when(toolLoopSpan1.name(anyString())).thenReturn(toolLoopSpan1);
            when(toolLoopSpan1.start()).thenReturn(toolLoopSpan1);
            when(toolLoopSpan1.tag(anyString(), anyString())).thenReturn(toolLoopSpan1);
            when(tracer.withSpan(toolLoopSpan1)).thenReturn(toolLoopScope);

            var toolLoopCtx1 = EmbabelObservationContext.toolLoop("run-1", ObservationKeys.toolLoopSpanName("interaction-1"));
            handler.onStart(toolLoopCtx1);
            handler.onScopeOpened(toolLoopCtx1);
            handler.onScopeClosed(toolLoopCtx1);
            handler.onStop(toolLoopCtx1);

            // Second tool loop - should still get action as parent
            when(tracer.nextSpan(actionSpan)).thenReturn(toolLoopSpan2);
            when(toolLoopSpan2.name(anyString())).thenReturn(toolLoopSpan2);
            when(toolLoopSpan2.start()).thenReturn(toolLoopSpan2);
            when(toolLoopSpan2.tag(anyString(), anyString())).thenReturn(toolLoopSpan2);

            var toolLoopCtx2 = EmbabelObservationContext.toolLoop("run-1", ObservationKeys.toolLoopSpanName("interaction-2"));
            handler.onStart(toolLoopCtx2);

            // Verify: both tool loop spans created with actionSpan as parent
            verify(tracer, atLeast(2)).nextSpan(actionSpan);
        }
    }

    // Verifies parallel LLM calls use thread-local currentSpan() for correct parenting
    @Nested
    @DisplayName("Parallel LLM_CALL support")
    class ParallelLlmCallSupport {

        @Test
        @DisplayName("TOOL_LOOP should prefer tracer.currentSpan() over LLM map lookup for parallel support")
        void toolLoop_shouldPreferCurrentSpan_overMapLookup() {
            Span agentSpan = createMockSpan("agent-span");
            Span actionSpan = createMockSpan("action-span");
            Span currentLlmSpan = createMockSpan("current-llm-span");
            Span toolLoopSpan = createMockSpan("tool-loop-span");

            Tracer.SpanInScope agentScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope actionScope = mock(Tracer.SpanInScope.class);

            // Agent start
            lenient().when(tracer.nextSpan()).thenReturn(agentSpan);
            lenient().when(agentSpan.name(anyString())).thenReturn(agentSpan);
            lenient().when(agentSpan.start()).thenReturn(agentSpan);
            lenient().when(tracer.withSpan(null)).thenReturn(agentScope);
            lenient().when(tracer.withSpan(agentSpan)).thenReturn(agentScope);

            var agentCtx = EmbabelObservationContext.rootAgent("run-1", "TestAgent");
            handler.onStart(agentCtx);
            handler.onScopeOpened(agentCtx);

            // Action start
            lenient().when(tracer.nextSpan(agentSpan)).thenReturn(actionSpan);
            lenient().when(actionSpan.name(anyString())).thenReturn(actionSpan);
            lenient().when(actionSpan.start()).thenReturn(actionSpan);
            lenient().when(tracer.withSpan(actionSpan)).thenReturn(actionScope);

            var actionCtx = EmbabelObservationContext.action("run-1", "MyAction");
            handler.onStart(actionCtx);
            handler.onScopeOpened(actionCtx);

            // No LLM in the map for this runId — but currentSpan() returns an LLM span
            // (simulating parallel scenario where the LLM span key was overwritten)
            lenient().when(tracer.currentSpan()).thenReturn(currentLlmSpan);
            lenient().when(tracer.nextSpan(currentLlmSpan)).thenReturn(toolLoopSpan);
            lenient().when(toolLoopSpan.name(anyString())).thenReturn(toolLoopSpan);
            lenient().when(toolLoopSpan.start()).thenReturn(toolLoopSpan);

            var toolLoopCtx = EmbabelObservationContext.toolLoop("run-1", ObservationKeys.toolLoopSpanName("int-1"));
            handler.onStart(toolLoopCtx);

            // The tool-loop should use currentLlmSpan as parent (from tracer.currentSpan())
            // NOT actionSpan from the map
            verify(tracer).nextSpan(currentLlmSpan);
        }
    }

    // Verifies sub-agent spans resolve parent to parent agent's span via parentRunId
    @Nested
    @DisplayName("AGENT_PROCESS (sub-agent) parent resolution")
    class SubAgentParentResolution {

        @Test
        @DisplayName("Sub-agent should use Action span as parent when Action is active")
        void subAgentSpan_shouldUseActionAsParent() {
            Span agentSpan = createMockSpan("agent-span");
            Span actionSpan = createMockSpan("action-span");
            Span subAgentSpan = createMockSpan("sub-agent-span");

            Tracer.SpanInScope agentScope = mock(Tracer.SpanInScope.class);
            Tracer.SpanInScope actionScope = mock(Tracer.SpanInScope.class);

            // Agent start: root span
            when(tracer.nextSpan()).thenReturn(agentSpan);
            when(agentSpan.name(anyString())).thenReturn(agentSpan);
            when(agentSpan.start()).thenReturn(agentSpan);
            when(tracer.withSpan(null)).thenReturn(agentScope);

            EmbabelObservationContext agentCtx = EmbabelObservationContext.rootAgent("parent-run", "ParentAgent");
            handler.onStart(agentCtx);
            when(tracer.withSpan(agentSpan)).thenReturn(agentScope);
            handler.onScopeOpened(agentCtx);

            // Action start: child of agent
            when(tracer.nextSpan(agentSpan)).thenReturn(actionSpan);
            when(actionSpan.name(anyString())).thenReturn(actionSpan);
            when(actionSpan.start()).thenReturn(actionSpan);

            EmbabelObservationContext actionCtx = EmbabelObservationContext.action("parent-run", "runSubAgent");
            handler.onStart(actionCtx);
            when(tracer.withSpan(actionSpan)).thenReturn(actionScope);
            handler.onScopeOpened(actionCtx);

            // Sub-agent start: should use action as parent (not agent)
            when(tracer.nextSpan(actionSpan)).thenReturn(subAgentSpan);
            when(subAgentSpan.name(anyString())).thenReturn(subAgentSpan);
            when(subAgentSpan.start()).thenReturn(subAgentSpan);

            EmbabelObservationContext subAgentCtx = EmbabelObservationContext.subAgent("child-run", "ChildAgent", "parent-run");
            handler.onStart(subAgentCtx);

            // Verify: sub-agent span was created with actionSpan as parent, NOT agentSpan
            verify(tracer).nextSpan(actionSpan);
        }

        @Test
        @DisplayName("Sub-agent should fall back to Agent span when no Action is active")
        void subAgentSpan_shouldFallbackToAgentSpan() {
            Span agentSpan = createMockSpan("agent-span");
            Span subAgentSpan = createMockSpan("sub-agent-span");

            Tracer.SpanInScope agentScope = mock(Tracer.SpanInScope.class);

            // Agent start: root span
            when(tracer.nextSpan()).thenReturn(agentSpan);
            when(agentSpan.name(anyString())).thenReturn(agentSpan);
            when(agentSpan.start()).thenReturn(agentSpan);
            when(tracer.withSpan(null)).thenReturn(agentScope);

            EmbabelObservationContext agentCtx = EmbabelObservationContext.rootAgent("parent-run", "ParentAgent");
            handler.onStart(agentCtx);
            when(tracer.withSpan(agentSpan)).thenReturn(agentScope);
            handler.onScopeOpened(agentCtx);

            // Sub-agent start (no action): should use agent as parent
            when(tracer.nextSpan(agentSpan)).thenReturn(subAgentSpan);
            when(subAgentSpan.name(anyString())).thenReturn(subAgentSpan);
            when(subAgentSpan.start()).thenReturn(subAgentSpan);

            EmbabelObservationContext subAgentCtx = EmbabelObservationContext.subAgent("child-run", "ChildAgent", "parent-run");
            handler.onStart(subAgentCtx);

            // Verify: sub-agent span was created with agentSpan as parent
            verify(tracer).nextSpan(agentSpan);
        }
    }

    private Span createMockSpan(String id) {
        Span span = mock(Span.class, id);
        TraceContext context = mock(TraceContext.class);
        lenient().when(span.context()).thenReturn(context);
        lenient().when(context.traceId()).thenReturn("trace-" + id);
        lenient().when(context.spanId()).thenReturn("span-" + id);
        lenient().when(span.tag(anyString(), anyString())).thenReturn(span);
        return span;
    }
}
