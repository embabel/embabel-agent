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

import com.embabel.agent.spi.support.ExecutorAsyncer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import kotlin.jvm.functions.Function0;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Safety-net test for the migration's core mechanism: direct {@code observe{}} instrumentation
 * plus Micrometer context propagation through {@link ExecutorAsyncer}. Proves, against a real
 * OpenTelemetry bridge and an {@link InMemorySpanExporter}, that:
 *
 * <ul>
 *   <li>work scheduled on a worker thread nests under the span active on the calling thread;</li>
 *   <li>an exception thrown inside observed work closes the span and marks it errored;</li>
 *   <li>concurrent runs stay isolated — each child nests under its own parent, never cross-linking.</li>
 * </ul>
 */
class MicrometerDirectInstrumentationConcurrencyTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private ObservationRegistry observationRegistry;
    private ExecutorService asyncerExecutor;
    private ExecutorAsyncer asyncer;
    private Scope otelRootScope;

    @BeforeEach
    void setUp() {
        otelRootScope = Context.root().makeCurrent();
        spanExporter = InMemorySpanExporter.create();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.noop())
                .build();

        OtelCurrentTraceContext otelCurrentTraceContext = new OtelCurrentTraceContext();
        io.opentelemetry.api.trace.Tracer otelTracer = openTelemetry.getTracer("test");
        OtelBaggageManager baggageManager = new OtelBaggageManager(
                otelCurrentTraceContext, Collections.emptyList(), Collections.emptyList());
        Tracer tracer = new OtelTracer(otelTracer, otelCurrentTraceContext, event -> {}, baggageManager);

        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultTracingObservationHandler(tracer));

        asyncerExecutor = Executors.newFixedThreadPool(4);
        asyncer = new ExecutorAsyncer(asyncerExecutor);
    }

    @AfterEach
    void tearDown() {
        if (asyncerExecutor != null) {
            asyncerExecutor.shutdownNow();
        }
        if (otelRootScope != null) {
            otelRootScope.close();
        }
        if (openTelemetry != null) {
            openTelemetry.close();
        }
        spanExporter.reset();
    }

    /** Run a child observation on a worker thread via the asyncer, returning when it completes. */
    private void childOnWorkerThread(String childName) {
        Function0<Object> work = () -> {
            Observation.createNotStarted(childName, observationRegistry)
                    .observe((Supplier<Object>) () -> null);
            return null;
        };
        asyncer.async(work).join();
    }

    @Test
    @DisplayName("work on a worker thread nests under the span active on the calling thread")
    void crossThreadNesting() {
        Observation.createNotStarted("embabel.action", observationRegistry)
                .observe(() -> childOnWorkerThread("embabel.llm"));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        Map<String, SpanData> byName = spans.stream()
                .collect(Collectors.toMap(SpanData::getName, s -> s));

        SpanData parent = byName.get("embabel.action");
        SpanData child = byName.get("embabel.llm");
        assertThat(parent).isNotNull();
        assertThat(child).isNotNull();
        // Child closed before parent (LLM finishes inside the action), but parentage is what matters:
        assertThat(child.getParentSpanId()).isEqualTo(parent.getSpanId());
        assertThat(child.getTraceId()).isEqualTo(parent.getTraceId());
        assertThat(parent.getParentSpanId()).isEqualTo(SpanId.getInvalid()); // root: no parent
        // Successful work must not error either span (guards against a stray error() call).
        assertThat(parent.getStatus().getStatusCode()).isNotEqualTo(StatusData.error().getStatusCode());
        assertThat(child.getStatus().getStatusCode()).isNotEqualTo(StatusData.error().getStatusCode());
    }

    @Test
    @DisplayName("an exception inside observed work closes the span and marks it errored")
    void exceptionClosesSpanAsError() {
        assertThatThrownBy(() ->
                Observation.createNotStarted("embabel.action", observationRegistry)
                        .observe((Supplier<Object>) () -> {
                            throw new IllegalStateException("boom");
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1); // span was closed despite the exception (no leak)
        SpanData span = spans.get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusData.error().getStatusCode());
        assertThat(span.getEvents())
                .anyMatch(e -> e.getName().equals("exception"));
    }

    @Test
    @DisplayName("an exception on a worker thread errors the child span and bubbles up to the caller")
    void crossThreadExceptionErrorsChildAndBubblesUp() {
        // A child observation runs (and fails) on a worker thread; the failure must error the child
        // span there, then propagate back through asyncer.join() and error the parent span too.
        assertThatThrownBy(() ->
                Observation.createNotStarted("embabel.action", observationRegistry)
                        .observe(() -> {
                            Function0<Object> work = () -> {
                                Observation.createNotStarted("embabel.llm", observationRegistry)
                                        .observe((Supplier<Object>) () -> {
                                            throw new IllegalStateException("worker boom");
                                        });
                                return null;
                            };
                            asyncer.async(work).join(); // re-throws the worker failure (wrapped)
                        }))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("worker boom");

        Map<String, SpanData> byName = spanExporter.getFinishedSpanItems().stream()
                .collect(Collectors.toMap(SpanData::getName, s -> s));
        SpanData child = byName.get("embabel.llm");
        SpanData parent = byName.get("embabel.action");
        assertThat(child).as("child llm span").isNotNull();
        assertThat(parent).as("parent action span").isNotNull();
        // Child still nests under parent despite failing on another thread.
        assertThat(child.getParentSpanId()).isEqualTo(parent.getSpanId());
        // Both spans are closed and errored — the worker failure was not swallowed at any hop.
        assertThat(child.getStatus().getStatusCode()).isEqualTo(StatusData.error().getStatusCode());
        assertThat(parent.getStatus().getStatusCode()).isEqualTo(StatusData.error().getStatusCode());
    }

    @Test
    @DisplayName("concurrent runs stay isolated: each child nests under its own parent")
    void concurrentRunsAreIsolated() throws Exception {
        int runs = 4;
        ExecutorService outer = Executors.newFixedThreadPool(runs);
        CyclicBarrier barrier = new CyclicBarrier(runs); // force all parents open simultaneously

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < runs; i++) {
            final int idx = i;
            futures.add(outer.submit(() ->
                    Observation.createNotStarted("agent-" + idx, observationRegistry)
                            .observe(() -> {
                                try {
                                    barrier.await();
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                childOnWorkerThread("llm-" + idx);
                            })));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        outer.shutdown();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(runs * 2);
        Map<String, SpanData> byName = spans.stream()
                .collect(Collectors.toMap(SpanData::getName, s -> s));

        for (int i = 0; i < runs; i++) {
            SpanData parent = byName.get("agent-" + i);
            SpanData child = byName.get("llm-" + i);
            assertThat(parent).as("parent agent-%d", i).isNotNull();
            assertThat(child).as("child llm-%d", i).isNotNull();
            assertThat(child.getParentSpanId())
                    .as("llm-%d nests under agent-%d", i, i)
                    .isEqualTo(parent.getSpanId());
            assertThat(child.getTraceId()).isEqualTo(parent.getTraceId());
        }
        // All four traces are distinct
        long distinctTraces = spans.stream().map(SpanData::getTraceId).distinct().count();
        assertThat(distinctTraces).isEqualTo(runs);
    }
}
