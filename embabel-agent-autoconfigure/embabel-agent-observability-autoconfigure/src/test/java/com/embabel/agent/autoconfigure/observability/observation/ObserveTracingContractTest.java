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
package com.embabel.agent.autoconfigure.observability.observation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the OpenTelemetry tracing contract the direct-instrumentation path relies on:
 * the {@code Observation.observe(...)} closure form, run through a
 * {@link DefaultTracingObservationHandler}, yields correct span semantics.
 *
 * <p>Because {@code observe} owns the scope (opens on start, closes on completion), it
 * never leaves a residual scope behind — so a top-level observation is a root span and
 * sequential observations get independent traces. That is precisely why the standard
 * {@link DefaultTracingObservationHandler} is sufficient and no custom handler is needed.
 *
 * <p>Span hierarchy under concurrency, cross-thread nesting and error propagation are
 * covered by {@code MicrometerDirectInstrumentationConcurrencyTest}.
 */
class ObserveTracingContractTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private Tracer tracer;
    private ObservationRegistry observationRegistry;
    private Scope otelRootScope;

    @BeforeEach
    void setUp() {
        // Force clean OTel context to prevent cross-test context leakage
        otelRootScope = Context.root().makeCurrent();

        // Create in-memory span exporter for testing
        spanExporter = InMemorySpanExporter.create();

        // Build OpenTelemetry SDK with the exporter
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();

        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.noop())
                .build();

        // Create Micrometer Tracer that bridges to OpenTelemetry
        OtelCurrentTraceContext otelCurrentTraceContext = new OtelCurrentTraceContext();
        io.opentelemetry.api.trace.Tracer otelTracer = openTelemetry.getTracer("test");

        // Create BaggageManager (required by OtelTracer)
        OtelBaggageManager baggageManager = new OtelBaggageManager(
                otelCurrentTraceContext,
                Collections.emptyList(),
                Collections.emptyList()
        );

        tracer = new OtelTracer(otelTracer, otelCurrentTraceContext, event -> {}, baggageManager);

        // Create ObservationRegistry and register the tracing handler
        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultTracingObservationHandler(tracer));
    }

    @AfterEach
    void tearDown() {
        spanExporter.reset();
        if (otelRootScope != null) {
            otelRootScope.close();
        }
    }

    private SpanData spanNamed(String name) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("observe() runs the work and emits one root span through the tracing handler")
    void observe_emitsRootSpanThroughHandler() {
        Observation.createNotStarted("test.observation", observationRegistry)
                .observe(() -> { /* work */ });

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("test.observation");
        assertThat(spans.get(0).getParentSpanId())
                .as("a top-level observe{} is a root span")
                .isEqualTo(SpanId.getInvalid());
    }

    @Test
    @DisplayName("sequential observe() blocks each get their own root trace (no residual scope leak)")
    void observe_sequentialBlocks_getIndependentRootTraces() {
        // Each request fully wraps in its own observe{}; the first must not leak its scope
        // into the second, so the second is a fresh root rather than a child of the first.
        Observation.createNotStarted("request.1", observationRegistry).observe(() -> { });
        Observation.createNotStarted("request.2", observationRegistry).observe(() -> { });

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(2);
        SpanData first = spanNamed("request.1");
        SpanData second = spanNamed("request.2");

        assertThat(first.getParentSpanId()).as("request.1 is root").isEqualTo(SpanId.getInvalid());
        assertThat(second.getParentSpanId()).as("request.2 is root").isEqualTo(SpanId.getInvalid());
        assertThat(first.getTraceId())
                .as("each request gets an independent trace")
                .isNotEqualTo(second.getTraceId());
    }

    @Test
    @DisplayName("a nested observe() becomes a child span in the same trace")
    void observe_nested_createsChildInSameTrace() {
        Observation.createNotStarted("parent", observationRegistry).observe(() ->
                Observation.createNotStarted("child", observationRegistry).observe(() -> { })
        );

        assertThat(spanExporter.getFinishedSpanItems()).hasSize(2);
        SpanData parent = spanNamed("parent");
        SpanData child = spanNamed("child");

        assertThat(parent.getParentSpanId()).as("parent is root").isEqualTo(SpanId.getInvalid());
        assertThat(child.getParentSpanId())
                .as("child nests under the active parent observation")
                .isEqualTo(parent.getSpanId());
        assertThat(child.getTraceId())
                .as("child shares the parent's trace")
                .isEqualTo(parent.getTraceId());
    }

    @Test
    @DisplayName("observation key-values are converted to span attributes")
    void observe_keyValues_becomeSpanAttributes() {
        Observation.createNotStarted("test.with.tags", observationRegistry)
                // low cardinality (e.g. agent.name) and high cardinality (e.g. input.value)
                // key-values both surface as OTel span attributes
                .lowCardinalityKeyValue("agent.name", "MyAgent")
                .lowCardinalityKeyValue("action.type", "LlmCall")
                .highCardinalityKeyValue("input.value", "Hello, World!")
                .observe(() -> { });

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("agent.name")))
                .isEqualTo("MyAgent");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("action.type")))
                .isEqualTo("LlmCall");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("input.value")))
                .isEqualTo("Hello, World!");
    }
}
