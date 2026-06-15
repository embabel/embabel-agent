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

import com.embabel.agent.api.event.LlmRequestEvent;
import com.embabel.agent.api.event.observation.ActionObservationContext;
import com.embabel.agent.api.event.observation.AgentObservationContext;
import com.embabel.agent.api.event.observation.LlmObservationContext;
import com.embabel.agent.autoconfigure.observability.EmbabelTracingContextPropagationRegistrar;
import com.embabel.agent.autoconfigure.observability.ObservabilityAutoConfiguration;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.agent.observability.observation.MicrometerAgentInstrumentation;
import com.embabel.agent.spi.support.ExecutorAsyncer;
import com.embabel.plan.Action;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.api.trace.SpanId;
import kotlin.jvm.functions.Function0;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression for the cross-thread orphaning of a span whose nearest live ancestor is reached only
 * through a DROPPED tier. The agent runs an action with {@code trace-action=false}; the action body
 * fans the LLM call out onto the {@link ExecutorAsyncer} pool. The dropped action is a no-op
 * observation carrying no span, so only {@code ObservationThreadLocalAccessor} (which propagates the
 * no-op observation, not the live span) is not enough — the live agent span must also cross the
 * thread boundary. Without {@code ObservationAwareSpanThreadLocalAccessor} registered, the LLM span
 * becomes an orphan root in a brand-new trace; with it, the LLM nests under the agent.
 */
class DroppedTierCrossThreadReParentTest {

    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private ObservationRegistry observationRegistry;
    private Tracer tracer;
    private ExecutorService asyncerExecutor;
    private ExecutorAsyncer asyncer;
    private Scope otelRootScope;
    private EmbabelTracingContextPropagationRegistrar registrar;

    @BeforeEach
    void setUp() throws Exception {
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
        OtelBaggageManager baggageManager = new OtelBaggageManager(
                otelCurrentTraceContext, Collections.emptyList(), Collections.emptyList());
        tracer = new OtelTracer(openTelemetry.getTracer("test"), otelCurrentTraceContext, e -> {}, baggageManager);

        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
        // The tier filter is applied per-test (dropped vs live action), see applyTierFilter(...).

        asyncerExecutor = Executors.newFixedThreadPool(4);
        asyncer = new ExecutorAsyncer(asyncerExecutor);

        // The production fix: register the span accessor on the global ContextRegistry so the live
        // span crosses the async boundary (ExecutorAsyncer captures via the global registry).
        registrar = new EmbabelTracingContextPropagationRegistrar(observationRegistry, tracer);
        registrar.afterPropertiesSet();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (registrar != null) registrar.destroy();
        if (asyncerExecutor != null) asyncerExecutor.shutdownNow();
        if (otelRootScope != null) otelRootScope.close();
        if (openTelemetry != null) openTelemetry.close();
        spanExporter.reset();
    }

    private void applyTierFilter(boolean traceAction) {
        ObservabilityProperties props = new ObservabilityProperties();
        props.setTraceAction(traceAction);
        new ObservabilityAutoConfiguration().embabelTierFilterCustomizer(props).customize(observationRegistry);
    }

    /**
     * Runs agent (live) &gt; action &gt; async hop &gt; llm (live) through the real adapter + asyncer, asserts
     * the LLM ran on a worker thread, and returns the exported spans.
     */
    private List<SpanData> runAgentActionAsyncLlm() {
        MicrometerAgentInstrumentation adapter = new MicrometerAgentInstrumentation(observationRegistry);
        long callerThreadId = Thread.currentThread().threadId();
        final long[] llmThreadId = {callerThreadId};

        adapter.observe((Function0<Observation.Context>) () -> new AgentObservationContext(mock(AgentProcess.class)),
                (Function0<Object>) () ->
                        adapter.observe((Function0<Observation.Context>) () ->
                                        new ActionObservationContext(mock(AgentProcess.class), mock(Action.class)),
                                (Function0<Object>) () ->
                                        // dispatch the LLM call onto the asyncer pool (a different thread)
                                        asyncer.async((Function0<Object>) () -> {
                                            llmThreadId[0] = Thread.currentThread().threadId();
                                            return adapter.observe(
                                                    (Function0<Observation.Context>) () ->
                                                            new LlmObservationContext(mock(LlmRequestEvent.class)),
                                                    (Function0<Object>) () -> null);
                                        }).join()));

        assertThat(llmThreadId[0]).as("LLM ran on a worker thread, not the caller").isNotEqualTo(callerThreadId);
        return spanExporter.getFinishedSpanItems();
    }

    @Test
    @DisplayName("trace-action=false + async LLM: the LLM span nests under the agent across the dropped action")
    void llmReParentsToAgentAcrossDroppedActionOnAnotherThread() {
        applyTierFilter(false); // DROP the action tier

        List<SpanData> spans = runAgentActionAsyncLlm();
        // action dropped => only agent + llm are exported
        assertThat(spans).as("agent + llm exported; action dropped").hasSize(2);

        SpanData agent = spans.stream()
                .filter(s -> !SpanId.isValid(s.getParentSpanId())).findFirst().orElseThrow();
        SpanData llm = spans.stream().filter(s -> s != agent).findFirst().orElseThrow();

        assertThat(llm.getTraceId())
                .as("the LLM must share the agent's trace, not start a new one")
                .isEqualTo(agent.getTraceId());
        assertThat(llm.getParentSpanId())
                .as("the LLM nests directly under the agent across the dropped action")
                .isEqualTo(agent.getSpanId());
    }

    @Test
    @DisplayName("NO REGRESSION: with the span accessor registered, the live-action path still nests agent > action > llm in one trace")
    void liveActionPathStillNestsCorrectlyWithAccessorRegistered() {
        applyTierFilter(true); // action stays LIVE (default)

        List<SpanData> spans = runAgentActionAsyncLlm();
        // all three tiers exported; the accessor must not double-scope or re-root anything
        assertThat(spans).as("agent + action + llm all exported").hasSize(3);
        assertThat(spans.stream().map(SpanData::getTraceId).distinct().count())
                .as("the whole chain stays in a single trace").isEqualTo(1L);

        SpanData agent = spans.stream()
                .filter(s -> !SpanId.isValid(s.getParentSpanId())).findFirst().orElseThrow();
        // the live action span sits between agent and llm
        SpanData action = spans.stream()
                .filter(s -> agent.getSpanId().equals(s.getParentSpanId())).findFirst().orElseThrow();
        SpanData llm = spans.stream()
                .filter(s -> action.getSpanId().equals(s.getParentSpanId())).findFirst().orElseThrow();

        assertThat(llm.getParentSpanId())
                .as("LLM nests under the live action (not re-rooted to the agent)")
                .isEqualTo(action.getSpanId());
    }
}
