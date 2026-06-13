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
package com.embabel.agent.autoconfigure.observability;

import com.embabel.agent.api.event.LlmRequestEvent;
import com.embabel.agent.api.event.ToolLoopStartEvent;
import com.embabel.agent.api.event.observation.ActionObservationContext;
import com.embabel.agent.api.event.observation.AgentObservationContext;
import com.embabel.agent.api.event.observation.LlmObservationContext;
import com.embabel.agent.api.event.observation.Observations;
import com.embabel.agent.api.event.observation.ToolLoopObservationContext;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.plan.Action;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies the tier-filter {@code ObservationPredicate}.
 *
 * <p>Two distinct matching strategies, because the name a span carries at predicate time differs by
 * how it was created:
 * <ul>
 *   <li><b>By context type</b> — the four core <em>scoped</em> spans (agent/action/tool_loop/llm) are
 *       created by the core via the by-name factory with a shared placeholder name; their semantic
 *       name (`embabel.agent`, …) is only applied later at {@code start()}, after the predicate has
 *       already run. So they cannot be matched by name — the predicate matches them by their typed
 *       {@code Observation.Context} ({@link AgentObservationContext}, …), which IS available at
 *       predicate time. Tests reproduce the real path ({@link #keptScoped}).</li>
 *   <li><b>By name</b> — Spring AI's {@code tool call} span and the {@code disabled-traces} entries
 *       carry their real name at creation (created via a convention / a real name), so a name match
 *       works for those.</li>
 * </ul>
 */
class TierFilterPredicateTest {

    private static final class RecordingHandler implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> stopped = new ArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }
    }

    private ObservationRegistry registry;
    private RecordingHandler handler;

    @BeforeEach
    void setUp() {
        registry = ObservationRegistry.create();
        handler = new RecordingHandler();
        registry.observationConfig().observationHandler(handler);
    }

    private void applyFilter(ObservabilityProperties properties) {
        new ObservabilityAutoConfiguration().embabelTierFilterCustomizer(properties).customize(registry);
    }

    // --- by-name path (Spring AI 'tool call', disabled-traces): the span has its real name at creation ---

    private boolean recorded(String name) {
        return handler.stopped.stream().anyMatch(c -> name.equals(c.getName()));
    }

    private void observe(String name) {
        Observation.createNotStarted(name, registry).observe(() -> {
        });
    }

    // --- faithful scoped-span path: exactly what Observations.observeOrSkip does in the core ---
    // The span is created with the placeholder name and a typed context; the predicate sees the
    // placeholder (not the semantic name), so it must match on the context type. Returns true if the
    // span survived the predicate (recorded), false if it was dropped.

    private boolean keptScoped(Observation.Context ctx) {
        Observation.createNotStarted(Observations.PLACEHOLDER_NAME, () -> ctx, registry)
                .observe(() -> {
                });
        return handler.stopped.contains(ctx);
    }

    private AgentObservationContext agentCtx() {
        return new AgentObservationContext(mock(AgentProcess.class));
    }

    private ActionObservationContext actionCtx() {
        return new ActionObservationContext(mock(AgentProcess.class), mock(Action.class));
    }

    private LlmObservationContext llmCtx() {
        return new LlmObservationContext(mock(LlmRequestEvent.class));
    }

    private ToolLoopObservationContext toolLoopCtx() {
        return new ToolLoopObservationContext(mock(ToolLoopStartEvent.class), List.of());
    }

    // --- Spring AI 'tool call' suppression (by name) ---

    @Test
    @DisplayName("Spring AI 'tool call' span is always dropped (Embabel emits its own embabel.tool)")
    void springAiToolCallAlwaysDropped() {
        applyFilter(new ObservabilityProperties());

        observe("tool call");

        assertFalse(recorded("tool call"), "Spring AI 'tool call' span is dropped unconditionally");
    }

    @Test
    @DisplayName("trace-tool-calls=false still drops 'tool call' (no gate reintroduced)")
    void toolCallStaysDroppedWhenTraceToolCallsDisabled() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceToolCalls(false);
        applyFilter(properties);

        observe("tool call");

        assertFalse(recorded("tool call"),
                "'tool call' is dropped regardless of trace-tool-calls; the rich embabel.tool span "
                        + "is the only tool span, gated by trace-tool-calls in the event listener");
    }

    @Test
    @DisplayName("name matching is exact: a name containing 'tool call' as a substring is kept")
    void substringDoesNotMatch() {
        applyFilter(new ObservabilityProperties());

        observe("tool call wrapper");

        assertTrue(recorded("tool call wrapper"), "only the exact 'tool call' name is dropped");
    }

    // --- core scoped spans (by context type), reproduced via the real path ---

    @Test
    @DisplayName("defaults keep all four core scoped spans")
    void defaultsKeepAllScopedSpans() {
        applyFilter(new ObservabilityProperties());

        assertTrue(keptScoped(agentCtx()), "embabel.agent kept");
        assertTrue(keptScoped(actionCtx()), "embabel.action kept");
        assertTrue(keptScoped(llmCtx()), "embabel.llm kept");
        assertTrue(keptScoped(toolLoopCtx()), "embabel.tool_loop kept");
    }

    @Test
    @DisplayName("trace-agent-events=false (umbrella) drops the whole core scoped tier")
    void traceAgentEventsFalseDropsWholeTier() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceAgentEvents(false);
        applyFilter(properties);

        assertFalse(keptScoped(agentCtx()), "embabel.agent dropped");
        assertFalse(keptScoped(actionCtx()), "embabel.action dropped");
        assertFalse(keptScoped(llmCtx()), "embabel.llm dropped");
        assertFalse(keptScoped(toolLoopCtx()), "embabel.tool_loop dropped");
    }

    @Test
    @DisplayName("trace-agent=false drops only embabel.agent")
    void traceAgentFalseDropsOnlyAgent() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceAgent(false);
        applyFilter(properties);

        assertFalse(keptScoped(agentCtx()), "embabel.agent dropped");
        assertTrue(keptScoped(actionCtx()), "embabel.action kept");
        assertTrue(keptScoped(llmCtx()), "embabel.llm kept");
        assertTrue(keptScoped(toolLoopCtx()), "embabel.tool_loop kept");
    }

    @Test
    @DisplayName("trace-action=false drops only embabel.action")
    void traceActionFalseDropsOnlyAction() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceAction(false);
        applyFilter(properties);

        assertTrue(keptScoped(agentCtx()), "embabel.agent kept");
        assertFalse(keptScoped(actionCtx()), "embabel.action dropped");
    }

    @Test
    @DisplayName("trace-llm-calls=false drops the scoped embabel.llm span")
    void traceLlmCallsFalseDropsScopedLlm() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceLlmCalls(false);
        applyFilter(properties);

        assertTrue(keptScoped(agentCtx()), "embabel.agent kept");
        assertFalse(keptScoped(llmCtx()), "scoped embabel.llm dropped by trace-llm-calls");
    }

    @Test
    @DisplayName("trace-tool-loop=false drops only embabel.tool_loop")
    void traceToolLoopFalseDropsOnlyToolLoop() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceToolLoop(false);
        applyFilter(properties);

        assertTrue(keptScoped(agentCtx()), "embabel.agent kept");
        assertFalse(keptScoped(toolLoopCtx()), "embabel.tool_loop dropped");
    }

    // --- point spans (plain context) are governed by their own flags in the listener, never here ---

    @Test
    @DisplayName("point spans are untouched even when the whole scoped tier is off")
    void pointSpansUnaffectedByScopedFlags() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceAgentEvents(false);
        applyFilter(properties);

        observe("embabel.embedding");
        observe("embabel.llm.invocation");

        assertTrue(recorded("embabel.embedding"), "point spans are unaffected by the scoped-tier flags");
        assertTrue(recorded("embabel.llm.invocation"));
    }

    // --- master switch: tracing-enabled=false suppresses Embabel spans, even with external tracing ---

    @Test
    @DisplayName("tracing-enabled=false suppresses all Embabel spans (scoped + embabel.* point), keeps non-Embabel")
    void tracingDisabledSuppressesEmbabelSpansOnly() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTracingEnabled(false);
        applyFilter(properties);

        // Embabel scoped spans (placeholder name + typed context) — suppressed
        assertFalse(keptScoped(agentCtx()), "embabel.agent suppressed");
        assertFalse(keptScoped(actionCtx()), "embabel.action suppressed");
        assertFalse(keptScoped(llmCtx()), "embabel.llm suppressed");
        assertFalse(keptScoped(toolLoopCtx()), "embabel.tool_loop suppressed");

        // Embabel point spans (named embabel.*) — suppressed
        observe("embabel.embedding");
        observe("embabel.llm.invocation");
        assertFalse(recorded("embabel.embedding"), "embabel.* point span suppressed");
        assertFalse(recorded("embabel.llm.invocation"));

        // Non-Embabel spans (the app's own Spring Boot / Spring AI tracing) — kept
        observe("http.server.requests");
        observe("chat gemini-2.5-pro");
        assertTrue(recorded("http.server.requests"), "non-Embabel spans are not Embabel's to suppress");
        assertTrue(recorded("chat gemini-2.5-pro"));
    }

    // --- disabled-traces (by name) ---

    @Test
    @DisplayName("disabled-traces drops the named observations and nothing else")
    void disabledTracesDropped() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setDisabledTraces(List.of("tasks.scheduled.execution", "http.server.requests"));
        applyFilter(properties);

        observe("tasks.scheduled.execution");
        observe("http.server.requests");

        assertFalse(recorded("tasks.scheduled.execution"), "listed observation is dropped");
        assertFalse(recorded("http.server.requests"), "listed observation is dropped");
        assertTrue(keptScoped(agentCtx()), "unlisted Embabel scoped span is kept");
    }

    @Test
    @DisplayName("disabled-traces does NOT target core scoped spans (placeholder name) — use the trace-* flags")
    void disabledTracesDoesNotTargetScopedSpans() {
        // The four core scoped spans carry the placeholder name at predicate time, so listing their
        // semantic name in disabled-traces has no effect by design — they are controlled by their
        // dedicated flags (here trace-action), not by disabled-traces.
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setDisabledTraces(List.of("embabel.action"));
        applyFilter(properties);

        assertTrue(keptScoped(actionCtx()),
                "disabled-traces=[embabel.action] does not drop the scoped span; trace-action=false would");
    }

    @Test
    @DisplayName("empty disabled-traces (default) suppresses nothing")
    void emptyDisabledTracesKeepsEverything() {
        applyFilter(new ObservabilityProperties());

        observe("tasks.scheduled.execution");

        assertTrue(recorded("tasks.scheduled.execution"), "no suppression by default");
    }
}
