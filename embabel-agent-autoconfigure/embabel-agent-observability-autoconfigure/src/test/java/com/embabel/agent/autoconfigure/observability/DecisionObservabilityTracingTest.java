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

import com.embabel.agent.decision.DecisionModel;
import com.embabel.agent.decision.DecisionProvenance;
import com.embabel.agent.decision.DecisionRequest;
import com.embabel.agent.decision.EvidenceKind;
import com.embabel.agent.decision.RawAnswer;
import com.embabel.agent.decision.RawDecisionOutcome;
import com.embabel.agent.observability.decision.DecisionMicrometerInstrumentation;
import io.micrometer.context.ContextRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
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
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionObservabilityTracingTest {
    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private ObservationRegistry observationRegistry;
    private Scope rootScope;

    @BeforeEach
    void setUp() {
        rootScope = Context.root().makeCurrent();
        spanExporter = InMemorySpanExporter.create();
        var tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.noop())
                .build();
        var currentTraceContext = new OtelCurrentTraceContext();
        var baggageManager = new OtelBaggageManager(
                currentTraceContext, Collections.emptyList(), Collections.emptyList());
        Tracer tracer = new OtelTracer(
                openTelemetry.getTracer("decision-test"), currentTraceContext, event -> {}, baggageManager);
        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultTracingObservationHandler(tracer));

        var accessor = ObservationThreadLocalAccessor.getInstance();
        accessor.setObservationRegistry(observationRegistry);
        ContextRegistry.getInstance().removeThreadLocalAccessor(ObservationThreadLocalAccessor.KEY);
        ContextRegistry.getInstance().registerThreadLocalAccessor(accessor);
    }

    @AfterEach
    void tearDown() {
        var accessor = ObservationThreadLocalAccessor.getInstance();
        ContextRegistry.getInstance().removeThreadLocalAccessor(ObservationThreadLocalAccessor.KEY);
        accessor.setObservationRegistry(ObservationRegistry.NOOP);
        ContextRegistry.getInstance().registerThreadLocalAccessor(accessor);
        if (openTelemetry != null) {
            openTelemetry.close();
        }
        if (rootScope != null) {
            rootScope.close();
        }
        spanExporter.reset();
    }

    @Test
    void providerSpanOnDecisionWorkerIsAChildOfTheDecisionSpan() {
        var workerObservation = new AtomicReference<Observation>();
        var adapter = new DecisionMicrometerInstrumentation(
                observationRegistry, new SimpleMeterRegistry(), true, false);
        try (var model = new DecisionModel(request -> {
            workerObservation.set(observationRegistry.getCurrentObservation());
            return Observation.createNotStarted("provider.transport", observationRegistry)
                    .observe(this::success);
        }).named("trace-model", "custom").withInstrumentation(adapter)) {
            Observation.createNotStarted("caller", observationRegistry).observe(() -> {
                assertThat(observationRegistry.getCurrentObservation()).isNotNull();
                model.ask(request());
                assertThat(observationRegistry.getCurrentObservation()).isNotNull();
            });
        }

        assertThat(workerObservation.get()).isNotNull();
        assertThat(observationRegistry.getCurrentObservation()).isNull();
        Map<String, SpanData> spans = spanExporter.getFinishedSpanItems().stream()
                .collect(Collectors.toMap(SpanData::getName, span -> span));
        assertThat(spans).containsOnlyKeys("caller", "embabel.decision", "provider.transport");
        assertThat(spans.get("caller").getParentSpanId()).isEqualTo(SpanId.getInvalid());
        assertThat(spans.get("embabel.decision").getParentSpanId())
                .isEqualTo(spans.get("caller").getSpanId());
        assertThat(spans.get("provider.transport").getParentSpanId())
                .isEqualTo(spans.get("embabel.decision").getSpanId());
        assertThat(spans.values()).extracting(SpanData::getTraceId).containsOnly(spans.get("caller").getTraceId());
    }

    private RawDecisionOutcome success() {
        return RawDecisionOutcome.success(
                List.of(RawAnswer.yesNo("approved", 0.8, "true")),
                DecisionProvenance.builder("provider", EvidenceKind.DISTRIBUTION).build());
    }

    private DecisionRequest request() {
        var builder = DecisionRequest.builder();
        builder.yesNo("approved", "Approve?");
        return builder.build();
    }
}
