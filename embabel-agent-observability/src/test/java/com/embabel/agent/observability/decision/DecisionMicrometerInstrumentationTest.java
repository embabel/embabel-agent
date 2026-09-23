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
package com.embabel.agent.observability.decision;

import com.embabel.agent.decision.api.CallFailure;
import com.embabel.agent.decision.api.DecisionModel;
import com.embabel.agent.decision.api.DecisionOutcome;
import com.embabel.agent.decision.api.DecisionProvenance;
import com.embabel.agent.decision.api.DecisionRequest;
import com.embabel.agent.decision.api.DecisionSafeCode;
import com.embabel.agent.decision.api.DecisionTelemetryEvent;
import com.embabel.agent.decision.api.EvidenceKind;
import com.embabel.agent.decision.api.KeyFailure;
import com.embabel.agent.decision.api.RawAnswer;
import com.embabel.agent.decision.api.RawDecisionOutcome;
import io.micrometer.context.ContextRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionMicrometerInstrumentationTest {
    private static final String CONTEXT_KEY = DecisionMicrometerInstrumentationTest.class.getName();
    private static final ThreadLocal<String> CALLER_CONTEXT = new ThreadLocal<>();

    @BeforeEach
    void registerCallerContext() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(CONTEXT_KEY);
        ContextRegistry.getInstance().registerThreadLocalAccessor(CONTEXT_KEY, CALLER_CONTEXT);
    }

    @AfterEach
    void removeCallerContext() {
        CALLER_CONTEXT.remove();
        ContextRegistry.getInstance().removeThreadLocalAccessor(CONTEXT_KEY);
    }

    @Test
    void recordsFiniteCallKeyAndEventMetrics() {
        var meters = new SimpleMeterRegistry();
        var observations = ObservationRegistry.create();
        var handler = new RecordingHandler();
        observations.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters))
                .observationHandler(handler);
        var adapter = new DecisionMicrometerInstrumentation(observations, meters, true, true);

        try (var model = new DecisionModel(request -> {
            for (DecisionTelemetryEvent event : DecisionTelemetryEvent.values()) {
                request.event(event);
            }
            return RawDecisionOutcome.success(
                    List.of(
                            RawAnswer.yesNo("approved", 0.8, "true"),
                            RawAnswer.failure("invalid", KeyFailure.Invalid, DecisionSafeCode.INVALID)),
                    DecisionProvenance.builder("sensitive-provider", EvidenceKind.DISTRIBUTION).build());
        }).named("sensitive-model-name", "custom").withInstrumentation(adapter)) {
            assertThat(model.ask(threeQuestionRequest())).isInstanceOf(DecisionOutcome.Success.class);
        }

        assertThat(counter(meters, "embabel.decision.calls.total", "provider.family", "custom",
                "outcome", "success", "safe.code", "none")).isEqualTo(1.0);
        assertThat(meters.get("embabel.decision.duration")
                .tags("provider.family", "custom", "outcome", "success", "safe.code", "none")
                .timer().count()).isEqualTo(1L);
        assertThat(counter(meters, "embabel.decision.keys.total", "provider.family", "custom",
                "key.outcome", "success")).isEqualTo(1.0);
        assertThat(counter(meters, "embabel.decision.keys.total", "provider.family", "custom",
                "key.outcome", "failure")).isEqualTo(2.0);
        for (DecisionTelemetryEvent event : DecisionTelemetryEvent.values()) {
            String eventName = event.name().toLowerCase(Locale.ROOT);
            assertThat(counter(meters, "embabel.decision.events.total", "provider.family", "custom",
                    "event", eventName)).as(eventName).isEqualTo(1.0);
        }
        assertThat(handler.started()).isEqualTo(1);
        assertThat(handler.stopped()).isEqualTo(1);
        assertThat(handler.events()).containsExactlyInAnyOrderElementsOf(
                java.util.Arrays.stream(DecisionTelemetryEvent.values())
                        .map(DecisionMicrometerInstrumentationTest::eventName)
                        .toList());
        assertThat(handler.lowCardinalityValues())
                .containsEntry("provider.family", "custom")
                .containsEntry("outcome", "success")
                .doesNotContainKeys("question.count", "key.success.count", "key.failure.count")
                .doesNotContainValue("sensitive-provider")
                .doesNotContainValue("sensitive-model-name")
                .doesNotContainValue("approved");
        assertThat(handler.highCardinalityValues())
                .containsEntry("question.count", "3")
                .containsEntry("key.success.count", "1")
                .containsEntry("key.failure.count", "2");
        var observationTimer = meters.get("embabel.decision")
                .tags("provider.family", "custom", "outcome", "success", "safe.code", "none")
                .timer();
        assertThat(observationTimer.count()).isEqualTo(1L);
        assertThat(observationTimer.getId().getTags())
                .extracting(tag -> tag.getKey())
                .doesNotContain("question.count", "key.success.count", "key.failure.count");
    }

    @Test
    void recordsEveryFiniteCallFailureCode() {
        var meters = new SimpleMeterRegistry();
        var adapter = new DecisionMicrometerInstrumentation(
                ObservationRegistry.NOOP, meters, false, true);
        Map<CallFailure, DecisionSafeCode> codes = new EnumMap<>(CallFailure.class);
        codes.put(CallFailure.Disabled, DecisionSafeCode.DISABLED);
        codes.put(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE);
        codes.put(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST);
        codes.put(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED);
        codes.put(CallFailure.Cancelled, DecisionSafeCode.CANCELLED);
        codes.put(CallFailure.Unsupported, DecisionSafeCode.UNSUPPORTED);

        for (var entry : codes.entrySet()) {
            try (var model = new DecisionModel(request ->
                    RawDecisionOutcome.failure(entry.getKey(), entry.getValue()))
                    .named("failure-model", "custom")
                    .withInstrumentation(adapter)) {
                assertThat(model.ask(oneQuestionRequest())).isInstanceOf(DecisionOutcome.Failure.class);
            }
            assertThat(counter(meters, "embabel.decision.calls.total", "provider.family", "custom",
                    "outcome", "failure", "safe.code", entry.getValue().name().toLowerCase(Locale.ROOT)))
                    .as(entry.getValue().name())
                    .isEqualTo(1.0);
        }
    }

    @Test
    void tracingAndMetricsGatesAreIndependent() {
        var traceOffMeters = new SimpleMeterRegistry();
        var traceOffRegistry = ObservationRegistry.create();
        var traceOffHandler = new RecordingHandler();
        traceOffRegistry.observationConfig().observationHandler(traceOffHandler);
        askOnce(new DecisionMicrometerInstrumentation(traceOffRegistry, traceOffMeters, false, true));
        assertThat(traceOffHandler.started()).isZero();
        assertThat(traceOffMeters.find("embabel.decision.calls.total").counter()).isNotNull();

        var metricsOffMeters = new SimpleMeterRegistry();
        var metricsOffRegistry = ObservationRegistry.create();
        var metricsOffHandler = new RecordingHandler();
        metricsOffRegistry.observationConfig().observationHandler(metricsOffHandler);
        askOnce(new DecisionMicrometerInstrumentation(metricsOffRegistry, metricsOffMeters, true, false));
        assertThat(metricsOffHandler.started()).isEqualTo(1);
        assertThat(metricsOffMeters.getMeters()).isEmpty();

        var disabledRegistry = ObservationRegistry.create();
        var disabledHandler = new RecordingHandler();
        disabledRegistry.observationConfig().observationHandler(disabledHandler);
        var disabledMeters = new SimpleMeterRegistry();
        askOnce(new DecisionMicrometerInstrumentation(disabledRegistry, disabledMeters, false, false));
        assertThat(disabledHandler.started()).isZero();
        assertThat(disabledMeters.getMeters()).isEmpty();
    }

    @Test
    void restoresCallerAndWorkerContextAfterFailuresAndAcrossConcurrentCalls() throws Exception {
        var observed = ConcurrentHashMap.<String>newKeySet();
        var barrier = new CyclicBarrier(4);
        var failFirst = new AtomicInteger();
        var meters = new SimpleMeterRegistry();
        var adapter = new DecisionMicrometerInstrumentation(
                ObservationRegistry.NOOP, meters, false, true);
        try (var model = new DecisionModel(request -> {
            String value = CALLER_CONTEXT.get();
            observed.add(value == null ? "<null>" : value);
            CALLER_CONTEXT.set("worker-residue");
            if (failFirst.getAndIncrement() == 0) {
                throw new IllegalStateException("provider detail must stay private");
            }
            if (value != null && value.startsWith("caller-")) {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return success();
        }).named("context", "custom").withInstrumentation(adapter)) {
            CALLER_CONTEXT.set("first");
            assertThat(model.ask(oneQuestionRequest())).isInstanceOf(DecisionOutcome.Failure.class);
            assertThat(CALLER_CONTEXT.get()).isEqualTo("first");

            ExecutorService callers = Executors.newFixedThreadPool(4);
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                String expected = "caller-" + index;
                futures.add(callers.submit(() -> {
                    CALLER_CONTEXT.set(expected);
                    try {
                        model.ask(oneQuestionRequest());
                        assertThat(CALLER_CONTEXT.get()).isEqualTo(expected);
                    } finally {
                        CALLER_CONTEXT.remove();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            callers.shutdownNow();

            CALLER_CONTEXT.remove();
            model.ask(oneQuestionRequest());
        }

        assertThat(observed).contains("first", "caller-0", "caller-1", "caller-2", "caller-3", "<null>");
        assertThat(observed).doesNotContain("worker-residue");
        assertThat(counter(meters, "embabel.decision.calls.total", "provider.family", "custom",
                "outcome", "success", "safe.code", "none")).isEqualTo(5.0);
        assertThat(counter(meters, "embabel.decision.calls.total", "provider.family", "custom",
                "outcome", "failure", "safe.code", "unavailable")).isEqualTo(1.0);
    }

    private static void askOnce(DecisionMicrometerInstrumentation adapter) {
        try (var model = new DecisionModel(request -> success())
                .named("one", "custom")
                .withInstrumentation(adapter)) {
            model.ask(oneQuestionRequest());
        }
    }

    private static RawDecisionOutcome success() {
        return RawDecisionOutcome.success(
                List.of(RawAnswer.yesNo("approved", 0.8, "true")),
                DecisionProvenance.builder("provider", EvidenceKind.DISTRIBUTION).build());
    }

    private static DecisionRequest oneQuestionRequest() {
        var request = DecisionRequest.builder();
        request.yesNo("approved", "Approve?");
        return request.build();
    }

    private static DecisionRequest threeQuestionRequest() {
        var request = DecisionRequest.builder();
        request.yesNo("approved", "Approve?");
        request.yesNo("invalid", "Invalid?");
        request.yesNo("missing", "Missing?");
        return request.build();
    }

    private static double counter(SimpleMeterRegistry registry, String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static String eventName(DecisionTelemetryEvent event) {
        return "decision." + event.name().toLowerCase(Locale.ROOT);
    }

    private static final class RecordingHandler implements ObservationHandler<Observation.Context> {
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger stopped = new AtomicInteger();
        private final Set<String> events = ConcurrentHashMap.newKeySet();
        private final AtomicReference<Map<String, String>> lowCardinalityValues =
                new AtomicReference<>(Map.of());
        private final AtomicReference<Map<String, String>> highCardinalityValues =
                new AtomicReference<>(Map.of());

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStart(Observation.Context context) {
            started.incrementAndGet();
        }

        @Override
        public void onEvent(Observation.Event event, Observation.Context context) {
            events.add(event.getName());
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.incrementAndGet();
            var values = new ConcurrentHashMap<String, String>();
            context.getLowCardinalityKeyValues().forEach(keyValue ->
                    values.put(keyValue.getKey(), keyValue.getValue()));
            lowCardinalityValues.set(Map.copyOf(values));
            var highCardinality = new ConcurrentHashMap<String, String>();
            context.getHighCardinalityKeyValues().forEach(keyValue ->
                    highCardinality.put(keyValue.getKey(), keyValue.getValue()));
            highCardinalityValues.set(Map.copyOf(highCardinality));
        }

        int started() {
            return started.get();
        }

        int stopped() {
            return stopped.get();
        }

        Set<String> events() {
            return Set.copyOf(events);
        }

        Map<String, String> lowCardinalityValues() {
            return lowCardinalityValues.get();
        }

        Map<String, String> highCardinalityValues() {
            return highCardinalityValues.get();
        }
    }
}
