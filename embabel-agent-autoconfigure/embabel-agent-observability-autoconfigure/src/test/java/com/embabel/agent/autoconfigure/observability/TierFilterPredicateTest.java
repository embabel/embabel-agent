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

import com.embabel.agent.observability.ObservabilityProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
        final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

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
        applyFilter(new ObservabilityProperties()); // defaults: trace-tool-calls=true

        observe("tool call");
        observe("embabel.tool_loop");

        assertFalse(recorded("tool call"), "Spring AI tool span dropped regardless of the flag");
        assertTrue(recorded("embabel.tool_loop"), "tool_loop unaffected by defaults");
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
