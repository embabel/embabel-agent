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

import com.embabel.agent.api.event.observation.Observations;
import com.embabel.agent.observability.ObservabilityProperties;
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

/**
 * Verifies the tier-filter ObservationPredicate: Spring AI's native {@code tool call} span is
 * always dropped (Embabel emits its own richer {@code embabel.tool} point span instead), and the
 * core-produced {@code embabel.tool_loop} span is dropped when {@code trace-tool-loop=false} — all
 * by name, without the core ever reading a flag.
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

    private boolean recorded(String name) {
        return handler.stopped.stream().anyMatch(c -> name.equals(c.getName()));
    }

    private void observe(String name) {
        Observation.createNotStarted(name, registry).observe(() -> {
        });
    }

    @Test
    @DisplayName("Spring AI 'tool call' span is always dropped (Embabel emits its own embabel.tool)")
    void springAiToolCallAlwaysDropped() {
        applyFilter(new ObservabilityProperties()); // defaults: trace-tool-loop=true

        observe("tool call");
        observe("embabel.tool_loop");

        assertFalse(recorded("tool call"), "Spring AI 'tool call' span is dropped unconditionally");
        assertTrue(recorded("embabel.tool_loop"), "tool_loop kept when trace-tool-loop=true");
    }

    @Test
    @DisplayName("trace-tool-loop=true explicitly keeps 'embabel.tool_loop'")
    void toolLoopKeptWhenExplicitlyEnabled() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceToolLoop(true);
        applyFilter(properties);

        observe("embabel.tool_loop");

        assertTrue(recorded("embabel.tool_loop"));
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
    @DisplayName("trace-tool-calls=true still drops 'tool call' (only embabel.tool survives)")
    void toolCallStaysDroppedWhenTraceToolCallsEnabled() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceToolCalls(true);
        applyFilter(properties);

        observe("tool call");

        assertFalse(recorded("tool call"), "'tool call' is dropped whether trace-tool-calls is true or false");
    }

    @Test
    @DisplayName("name matching is exact: a name containing 'tool call' as a substring is kept")
    void substringDoesNotMatch() {
        applyFilter(new ObservabilityProperties());

        observe("tool call wrapper");

        assertTrue(recorded("tool call wrapper"), "only the exact 'tool call' name is dropped");
    }

    @Test
    @DisplayName("trace-tool-loop=false drops 'embabel.tool_loop'; 'tool call' stays dropped")
    void toolLoopSuppressed() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceToolLoop(false);
        applyFilter(properties);

        observe("tool call");
        observe("embabel.tool_loop");

        assertFalse(recorded("embabel.tool_loop"));
        assertFalse(recorded("tool call"));
    }

    @Test
    @DisplayName("trace-agent-events=false drops the core scoped span tier (placeholder name)")
    void traceAgentEventsFalseDropsCoreScopedTier() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceAgentEvents(false);
        applyFilter(properties);

        // With the conventions un-registered, agent/action/tool_loop/llm all carry the placeholder
        // name; dropping it suppresses the whole core scoped span tier in one rule.
        observe(Observations.PLACEHOLDER_NAME);

        assertFalse(recorded(Observations.PLACEHOLDER_NAME),
                "trace-agent-events=false suppresses the core scoped spans, not just their attributes");
    }

    @Test
    @DisplayName("trace-agent-events=true (default) keeps the core scoped spans")
    void traceAgentEventsTrueKeepsCoreScopedTier() {
        applyFilter(new ObservabilityProperties()); // default: trace-agent-events=true

        observe(Observations.PLACEHOLDER_NAME);

        assertTrue(recorded(Observations.PLACEHOLDER_NAME));
    }

    @Test
    @DisplayName("trace-agent-events=false leaves point spans untouched (only the core tier is dropped)")
    void traceAgentEventsFalseKeepsPointSpans() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceAgentEvents(false);
        applyFilter(properties);

        observe("embabel.embedding");
        observe("embabel.llm.invocation");

        assertTrue(recorded("embabel.embedding"), "point spans are unaffected by trace-agent-events");
        assertTrue(recorded("embabel.llm.invocation"));
    }

    @Test
    @DisplayName("trace-agent=false drops only embabel.agent, leaving action/llm/tool_loop")
    void traceAgentFalseDropsOnlyAgent() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceAgent(false);
        applyFilter(properties);

        observe("embabel.agent");
        observe("embabel.action");
        observe("embabel.llm");
        observe("embabel.tool_loop");

        assertFalse(recorded("embabel.agent"), "embabel.agent dropped");
        assertTrue(recorded("embabel.action"), "embabel.action kept");
        assertTrue(recorded("embabel.llm"), "embabel.llm kept");
        assertTrue(recorded("embabel.tool_loop"), "embabel.tool_loop kept");
    }

    @Test
    @DisplayName("trace-action=false drops only embabel.action")
    void traceActionFalseDropsOnlyAction() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceAction(false);
        applyFilter(properties);

        observe("embabel.agent");
        observe("embabel.action");

        assertTrue(recorded("embabel.agent"), "embabel.agent kept");
        assertFalse(recorded("embabel.action"), "embabel.action dropped");
    }

    @Test
    @DisplayName("trace-llm-calls=false drops the scoped embabel.llm span")
    void traceLlmCallsFalseDropsScopedLlm() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setTraceLlmCalls(false);
        applyFilter(properties);

        observe("embabel.agent");
        observe("embabel.llm");

        assertTrue(recorded("embabel.agent"), "embabel.agent kept");
        assertFalse(recorded("embabel.llm"), "scoped embabel.llm dropped by trace-llm-calls");
    }

    @Test
    @DisplayName("a normal span (embabel.llm) is never dropped")
    void normalSpanKept() {
        applyFilter(new ObservabilityProperties());

        observe("embabel.llm");
        observe("embabel.tool_loop");

        assertTrue(recorded("embabel.llm"));
        assertTrue(recorded("embabel.tool_loop"));
    }

    @Test
    @DisplayName("disabled-traces drops the named observations and nothing else")
    void disabledTracesDropped() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.setDisabledTraces(List.of("tasks.scheduled.execution", "http.server.requests"));
        applyFilter(properties);

        observe("tasks.scheduled.execution");
        observe("http.server.requests");
        observe("embabel.llm");

        assertFalse(recorded("tasks.scheduled.execution"), "listed observation is dropped");
        assertFalse(recorded("http.server.requests"), "listed observation is dropped");
        assertTrue(recorded("embabel.llm"), "unlisted Embabel span is kept");
    }

    @Test
    @DisplayName("empty disabled-traces (default) suppresses nothing")
    void emptyDisabledTracesKeepsEverything() {
        applyFilter(new ObservabilityProperties()); // defaults: disabled-traces empty

        observe("tasks.scheduled.execution");
        observe("embabel.llm");

        assertTrue(recorded("tasks.scheduled.execution"), "no suppression by default");
        assertTrue(recorded("embabel.llm"));
    }
}
