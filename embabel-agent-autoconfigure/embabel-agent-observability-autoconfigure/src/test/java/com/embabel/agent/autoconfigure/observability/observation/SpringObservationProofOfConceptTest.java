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
 * Proof-of-concept test for using Spring Observation API for both traces AND metrics.
 *
 * <p>This test validates:
 * <ol>
 *   <li>ObservationRegistry with DefaultTracingObservationHandler works correctly</li>
 *   <li>The pattern: withSpan(null) + observation.start() creates root spans without parent</li>
 *   <li>Nested observations create proper parent-child span relationships</li>
 *   <li>Each "request" gets its own trace ID when using the root span pattern</li>
 * </ol>
 */
class SpringObservationProofOfConceptTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private Tracer tracer;
    private ObservationRegistry observationRegistry;

    @BeforeEach
    void setUp() {
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
    }

    // Smoke test: observation creates a span through the handler
    @Test
    @DisplayName("ObservationRegistry should have DefaultTracingObservationHandler registered")
    void observationRegistry_shouldHaveTracingHandlerRegistered() {
        // Create a simple observation to verify handler works
        Observation observation = Observation.createNotStarted("test.observation", observationRegistry);
        observation.start();
        observation.stop();

        // Verify span was created
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("test.observation");
    }

    // Verifies that opening scope on a parent before creating a child establishes hierarchy
    @Test
    @DisplayName("Nested observations should create parent-child span relationships")
    void nestedObservations_shouldCreateParentChildRelationships() {
        // Start parent observation
        Observation parent = Observation.createNotStarted("parent", observationRegistry);
        parent.start();
        Observation.Scope parentScope = parent.openScope();

        // Start child observation (should automatically be child of parent)
        Observation child = Observation.createNotStarted("child", observationRegistry);
        child.start();
        Observation.Scope childScope = child.openScope();

        // Clean up - close in reverse order
        childScope.close();
        child.stop();
        parentScope.close();
        parent.stop();

        // Verify hierarchy
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData childSpan = spans.stream()
                .filter(s -> s.getName().equals("child"))
                .findFirst()
                .orElseThrow();
        SpanData parentSpan = spans.stream()
                .filter(s -> s.getName().equals("parent"))
                .findFirst()
                .orElseThrow();

        // Child should have parent's span ID as its parent
        assertThat(childSpan.getParentSpanId()).isEqualTo(parentSpan.getSpanId());
        // Same trace ID
        assertThat(childSpan.getTraceId()).isEqualTo(parentSpan.getTraceId());
    }

    // Documents that Micrometer's withSpan(null) does NOT clear OTel context -- the span still gets a parent
    @Test
    @DisplayName("withSpan(null) before observation.start() - KNOWN LIMITATION: does NOT work")
    void withSpanNull_beforeObservationStart_knownLimitation() {
        // First, create a "background" span that would normally be the parent
        Observation background = Observation.createNotStarted("background", observationRegistry);
        background.start();
        Observation.Scope bgScope = background.openScope();

        // Now create a new "root" span using the withSpan(null) pattern
        // This simulates a new user request that should get its own trace
        Observation rootObservation = Observation.createNotStarted("new.root", observationRegistry);

        // Using Micrometer's withSpan(null) does NOT clear the OpenTelemetry context
        // that DefaultTracingObservationHandler uses
        try (Tracer.SpanInScope ignored = tracer.withSpan(null)) {
            rootObservation.start();
        }
        Observation.Scope rootScope = rootObservation.openScope();

        // Clean up
        rootScope.close();
        rootObservation.stop();
        bgScope.close();
        background.stop();

        // Verify
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData bgSpan = spans.stream()
                .filter(s -> s.getName().equals("background"))
                .findFirst()
                .orElseThrow();
        SpanData rootSpan = spans.stream()
                .filter(s -> s.getName().equals("new.root"))
                .findFirst()
                .orElseThrow();

        // KNOWN LIMITATION: The span STILL has a parent because
        // DefaultTracingObservationHandler uses OpenTelemetry context directly
        // withSpan(null) only clears the Micrometer context, not the OTel context
        assertThat(rootSpan.getParentSpanId())
                .as("Micrometer withSpan(null) does NOT clear OTel context - span still has parent")
                .isNotEqualTo(io.opentelemetry.api.trace.SpanId.getInvalid());
    }

    // Demonstrates the workaround: using Tracer API directly (not Observation API) with withSpan(null) DOES create root spans
    @Test
    @DisplayName("Direct Tracer nextSpan inside withSpan(null) - WORKS for root spans")
    void directTracerNextSpan_insideWithSpanNull_createsRootSpan() {
        // First, create a "background" span that would be the parent
        io.micrometer.tracing.Span bgSpan = tracer.nextSpan().name("background.direct").start();
        try (Tracer.SpanInScope bgScope = tracer.withSpan(bgSpan)) {

            // Create a root span using direct Tracer API with withSpan(null)
            io.micrometer.tracing.Span rootSpan;
            try (Tracer.SpanInScope ignored = tracer.withSpan(null)) {
                rootSpan = tracer.nextSpan().name("root.direct").start();
            }

            // Now we can make the root span current and do child work
            try (Tracer.SpanInScope rootScope = tracer.withSpan(rootSpan)) {
                // Child span under root
                io.micrometer.tracing.Span childSpan = tracer.nextSpan().name("child.direct").start();
                childSpan.end();
            }
            rootSpan.end();
        }
        bgSpan.end();

        // Verify
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(3);

        SpanData bgSpanData = spans.stream()
                .filter(s -> s.getName().equals("background.direct"))
                .findFirst()
                .orElseThrow();
        SpanData rootSpanData = spans.stream()
                .filter(s -> s.getName().equals("root.direct"))
                .findFirst()
                .orElseThrow();
        SpanData childSpanData = spans.stream()
                .filter(s -> s.getName().equals("child.direct"))
                .findFirst()
                .orElseThrow();

        // Root span should have NO parent
        assertThat(rootSpanData.getParentSpanId())
                .as("Root span should have no parent")
                .isEqualTo(io.opentelemetry.api.trace.SpanId.getInvalid());

        // Root and background should have DIFFERENT trace IDs
        assertThat(rootSpanData.getTraceId())
                .as("Root span should have different trace ID than background")
                .isNotEqualTo(bgSpanData.getTraceId());

        // Child should be under root
        assertThat(childSpanData.getParentSpanId())
                .as("Child should have root as parent")
                .isEqualTo(rootSpanData.getSpanId());
        assertThat(childSpanData.getTraceId())
                .as("Child should be in same trace as root")
                .isEqualTo(rootSpanData.getTraceId());
    }

    // Documents that neither Micrometer nor OTel context clearing helps when using Observation API -- this is WHY EmbabelTracingObservationHandler exists
    @Test
    @DisplayName("Spring Observation API CANNOT create root spans with context clearing")
    void springObservation_cannotCreateRootSpans_documentedLimitation() {
        // This test documents a LIMITATION of the Spring Observation API:
        // When using DefaultTracingObservationHandler, it's not possible to
        // create a root span (without parent) while another span is in scope.

        // First, create a "background" span
        Observation background = Observation.createNotStarted("background", observationRegistry);
        background.start();
        Observation.Scope bgScope = background.openScope();

        // Try to create a root observation - clearing contexts does NOT work
        Observation rootObservation = Observation.createNotStarted("attempted.root", observationRegistry);
        try (Tracer.SpanInScope ignored = tracer.withSpan(null);
             Scope otelIgnored = Context.root().makeCurrent()) {
            rootObservation.start();
        }
        Observation.Scope rootScope = rootObservation.openScope();

        // Clean up
        rootScope.close();
        rootObservation.stop();
        bgScope.close();
        background.stop();

        // Verify - the "root" observation actually becomes a child
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData bgSpanData = spans.stream()
                .filter(s -> s.getName().equals("background"))
                .findFirst()
                .orElseThrow();
        SpanData attemptedRootSpanData = spans.stream()
                .filter(s -> s.getName().equals("attempted.root"))
                .findFirst()
                .orElseThrow();

        // DOCUMENTED LIMITATION: The observation IS a child, not a root
        assertThat(attemptedRootSpanData.getParentSpanId())
                .as("Spring Observation API cannot create root spans - this is a limitation")
                .isEqualTo(bgSpanData.getSpanId());
    }

    // Verifies that sequential requests each get separate trace IDs (no trace leaking between requests)
    @Test
    @DisplayName("Multiple requests should each get their own trace ID")
    void multipleRequests_shouldEachGetOwnTraceId() {
        String traceId1;
        String traceId2;

        // Simulate first request
        {
            Observation request1 = Observation.createNotStarted("request.1", observationRegistry);
            try (Tracer.SpanInScope ignored = tracer.withSpan(null)) {
                request1.start();
            }
            Observation.Scope scope = request1.openScope();

            // Nested action in request 1
            Observation action1 = Observation.createNotStarted("action.1", observationRegistry);
            action1.start();
            Observation.Scope actionScope = action1.openScope();
            actionScope.close();
            action1.stop();

            scope.close();
            request1.stop();
        }

        // Simulate second request
        {
            Observation request2 = Observation.createNotStarted("request.2", observationRegistry);
            try (Tracer.SpanInScope ignored = tracer.withSpan(null)) {
                request2.start();
            }
            Observation.Scope scope = request2.openScope();

            // Nested action in request 2
            Observation action2 = Observation.createNotStarted("action.2", observationRegistry);
            action2.start();
            Observation.Scope actionScope = action2.openScope();
            actionScope.close();
            action2.stop();

            scope.close();
            request2.stop();
        }

        // Verify
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(4);

        SpanData request1Span = spans.stream()
                .filter(s -> s.getName().equals("request.1"))
                .findFirst()
                .orElseThrow();
        SpanData action1Span = spans.stream()
                .filter(s -> s.getName().equals("action.1"))
                .findFirst()
                .orElseThrow();
        SpanData request2Span = spans.stream()
                .filter(s -> s.getName().equals("request.2"))
                .findFirst()
                .orElseThrow();
        SpanData action2Span = spans.stream()
                .filter(s -> s.getName().equals("action.2"))
                .findFirst()
                .orElseThrow();

        traceId1 = request1Span.getTraceId();
        traceId2 = request2Span.getTraceId();

        // Each request should have its own trace ID
        assertThat(traceId1)
                .as("Request 1 and Request 2 should have different trace IDs")
                .isNotEqualTo(traceId2);

        // Actions should be in the same trace as their parent request
        assertThat(action1Span.getTraceId())
                .as("Action 1 should be in same trace as Request 1")
                .isEqualTo(traceId1);
        assertThat(action2Span.getTraceId())
                .as("Action 2 should be in same trace as Request 2")
                .isEqualTo(traceId2);

        // Actions should be children of their respective requests
        assertThat(action1Span.getParentSpanId())
                .as("Action 1 should be child of Request 1")
                .isEqualTo(request1Span.getSpanId());
        assertThat(action2Span.getParentSpanId())
                .as("Action 2 should be child of Request 2")
                .isEqualTo(request2Span.getSpanId());
    }

    // Verifies that low/high cardinality key-values become OTel span attributes
    @Test
    @DisplayName("Observation attributes should be converted to span tags")
    void observationAttributes_shouldBeConvertedToSpanTags() {
        Observation observation = Observation.createNotStarted("test.with.tags", observationRegistry);

        // Add low cardinality key-values (become span tags)
        observation.lowCardinalityKeyValue("agent.name", "MyAgent");
        observation.lowCardinalityKeyValue("action.type", "LlmCall");

        // Add high cardinality key-values (also become span tags)
        observation.highCardinalityKeyValue("input.value", "Hello, World!");

        observation.start();
        observation.stop();

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
