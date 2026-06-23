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

import com.embabel.agent.api.event.observation.AgentInstrumentation;
import com.embabel.agent.api.event.observation.NoOpAgentInstrumentation;
import com.embabel.agent.observability.tracing.MicrometerAgentInstrumentation;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guard tests for the {@link AgentInstrumentation} SPI inversion (the LIGHT migration). They exercise
 * the actual port, not the raw Micrometer API, against a real OpenTelemetry bridge +
 * {@link InMemorySpanExporter}:
 *
 * <ul>
 *   <li><b>Leak guard.</b> With {@link NoOpAgentInstrumentation} (i.e. observability module absent),
 *       the work still runs but <b>no span is exported</b> — even though a real registry/exporter is
 *       wired. This is what makes "no module = no embabel spans" structural.</li>
 *   <li><b>Parallel guard.</b> Through the production {@link MicrometerAgentInstrumentation} adapter,
 *       children spawned on worker threads via {@link ExecutorAsyncer} nest correctly under the
 *       parent span (right {@code parentSpanId}, same {@code traceId}). Guards that the SPI indirection
 *       did not break cross-thread parent propagation.</li>
 * </ul>
 */
class AgentInstrumentationSpiTracingTest {

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

    @Test
    @DisplayName("LEAK GUARD: NoOp instrumentation runs the work but exports no span (no module)")
    void noOpInstrumentationProducesNoSpan() {
        AgentInstrumentation noop = NoOpAgentInstrumentation.INSTANCE;
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicBoolean contextBuilt = new AtomicBoolean(false);

        String result = noop.observe(
                () -> {
                    contextBuilt.set(true);
                    return new Observation.Context();
                },
                () -> {
                    ran.set(true);
                    return "done";
                });

        assertThat(result).isEqualTo("done");
        assertThat(ran).as("work must still run under NoOp").isTrue();
        assertThat(contextBuilt).as("NoOp must not even build the context").isFalse();
        // The whole point: a real registry/exporter is wired, yet NOTHING is exported via the port.
        assertThat(spanExporter.getFinishedSpanItems())
                .as("no embabel span when the module (adapter) is absent")
                .isEmpty();
    }

    @Test
    @DisplayName("CONTROL: the Micrometer adapter does export a span for the same call")
    void micrometerAdapterProducesSpan() {
        AgentInstrumentation adapter = new MicrometerAgentInstrumentation(observationRegistry);

        adapter.observe((Function0<Observation.Context>) Observation.Context::new,
                (Function0<Object>) () -> null);

        assertThat(spanExporter.getFinishedSpanItems())
                .as("adapter present => span exported")
                .hasSize(1);
    }

    @Test
    @DisplayName("SCOPE-LEAK GUARD: an exception in observed work closes the span and marks it errored through the adapter")
    void adapterExceptionClosesAndErrorsSpan() {
        AgentInstrumentation adapter = new MicrometerAgentInstrumentation(observationRegistry);

        // The raw observe{} error contract is covered by MicrometerDirectInstrumentationConcurrencyTest;
        // here we prove the SPI indirection preserves it: the work throws, the exception propagates, yet
        // the span is still closed (no scope leak) and carries ERROR status.
        assertThatThrownBy(() ->
                adapter.observe((Function0<Observation.Context>) Observation.Context::new,
                        (Function0<Object>) () -> {
                            throw new IllegalStateException("boom");
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).as("the span is still closed despite the exception (no scope leak)").hasSize(1);
        assertThat(spans.get(0).getStatus().getStatusCode())
                .as("the span is marked errored")
                .isEqualTo(StatusData.error().getStatusCode());
    }

    @Test
    @DisplayName("PARALLEL GUARD: children opened simultaneously on OTHER threads nest under the parent via the adapter")
    void parallelChildrenNestUnderParentThroughAdapter() throws Exception {
        AgentInstrumentation adapter = new MicrometerAgentInstrumentation(observationRegistry);
        int children = 4;
        long callerThreadId = Thread.currentThread().threadId();
        // Barrier forces all `children` to be in-flight on distinct pool threads at the SAME time before
        // any opens its span — so this proves true concurrency, not sequential reuse of one pool thread.
        CyclicBarrier barrier = new CyclicBarrier(children);
        Set<Long> childThreadIds = ConcurrentHashMap.newKeySet();

        // Parent span opened through the adapter; inside it we fan out `children` work items on the
        // asyncer, each opening its own span through the adapter. ExecutorAsyncer must carry the current
        // observation across threads so every child nests under the parent.
        adapter.observe((Function0<Observation.Context>) Observation.Context::new, () -> {
            List<CompletableFuture<Object>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < children; i++) {
                Function0<Object> childWork = () -> {
                    childThreadIds.add(Thread.currentThread().threadId());
                    try {
                        barrier.await(); // all children must reach here together => real parallelism
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return adapter.observe((Function0<Observation.Context>) Observation.Context::new,
                            (Function0<Object>) () -> null);
                };
                futures.add(asyncer.async(childWork));
            }
            futures.forEach(CompletableFuture::join);
            return null;
        });

        // Cross-thread proof: children ran on worker threads, none on the caller thread.
        assertThat(childThreadIds).as("children ran on >1 distinct worker thread").hasSizeGreaterThan(1);
        assertThat(childThreadIds).as("no child ran on the calling thread").doesNotContain(callerThreadId);

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).as("parent + %d children", children).hasSize(children + 1);

        List<SpanData> roots = spans.stream()
                .filter(s -> !SpanId.isValid(s.getParentSpanId()))
                .collect(Collectors.toList());
        assertThat(roots).as("exactly one root (the parent)").hasSize(1);
        SpanData parent = roots.get(0);

        List<SpanData> nested = spans.stream()
                .filter(s -> SpanId.isValid(s.getParentSpanId()))
                .collect(Collectors.toList());
        assertThat(nested).as("all %d children are non-root", children).hasSize(children);
        assertThat(nested).allSatisfy(child -> {
            assertThat(child.getParentSpanId())
                    .as("child nests under the parent")
                    .isEqualTo(parent.getSpanId());
            assertThat(child.getTraceId())
                    .as("child shares the parent's trace")
                    .isEqualTo(parent.getTraceId());
        });
        // One single trace across the whole parallel fan-out.
        assertThat(spans.stream().map(SpanData::getTraceId).distinct().count()).isEqualTo(1);
    }
}
