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

import com.embabel.agent.decision.api.DecisionCompletion;
import com.embabel.agent.decision.api.DecisionInstrumentation;
import com.embabel.agent.decision.api.DecisionObservation;
import com.embabel.agent.decision.api.DecisionObservationContext;
import com.embabel.agent.decision.api.DecisionProviderFamily;
import com.embabel.agent.decision.api.DecisionTelemetryEvent;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Micrometer bridge for the framework-neutral decision instrumentation port. */
@ApiStatus.Experimental
public final class DecisionMicrometerInstrumentation implements DecisionInstrumentation {
    private static final String OBSERVATION_NAME = "embabel.decision";
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final boolean tracingEnabled;
    private final boolean metricsEnabled;
    private final ContextSnapshotFactory contextSnapshotFactory;

    public DecisionMicrometerInstrumentation(
            ObservationRegistry observationRegistry,
            MeterRegistry meterRegistry,
            boolean tracingEnabled,
            boolean metricsEnabled) {
        this.observationRegistry = Objects.requireNonNull(observationRegistry);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.tracingEnabled = tracingEnabled;
        this.metricsEnabled = metricsEnabled;
        this.contextSnapshotFactory = ContextSnapshotFactory.builder().clearMissing(true).build();
    }

    public static DecisionMicrometerInstrumentation disabled() {
        return new DecisionMicrometerInstrumentation(
                ObservationRegistry.NOOP,
                new CompositeMeterRegistry(),
                false,
                false);
    }

    @Override
    public DecisionObservation start(DecisionObservationContext context) {
        Observation observation = null;
        Observation.Scope scope = null;
        if (tracingEnabled) {
            observation = Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
                    .lowCardinalityKeyValue("provider.family", family(context.getProviderFamily()))
                    .highCardinalityKeyValue("question.count", Integer.toString(context.getQuestionCount()))
                    .start();
            scope = observation.openScope();
        }
        return new Session(observation, scope, context.getProviderFamily());
    }

    private final class Session implements DecisionObservation {
        private final Observation observation;
        private final Observation.Scope scope;
        private final DecisionProviderFamily providerFamily;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private Session(
                Observation observation,
                Observation.Scope scope,
                DecisionProviderFamily providerFamily) {
            this.observation = observation;
            this.scope = scope;
            this.providerFamily = providerFamily;
        }

        @Override
        public <T> Callable<T> wrap(Callable<T> work) {
            ContextSnapshot callerContext = contextSnapshotFactory.captureAll();
            return () -> {
                try (ContextSnapshot.Scope ignored = callerContext.setThreadLocals()) {
                    return work.call();
                }
            };
        }

        @Override
        public void event(DecisionTelemetryEvent event) {
            String eventValue = value(event);
            if (observation != null) {
                observation.event(Observation.Event.of("decision." + eventValue));
            }
            if (metricsEnabled) {
                Counter.builder("embabel.decision.events.total")
                        .tag("provider.family", family(providerFamily))
                        .tag("event", eventValue)
                        .register(meterRegistry)
                        .increment();
            }
        }

        @Override
        public void complete(DecisionCompletion completion) {
            String outcome = value(completion.getStatus());
            String safeCode = completion.getSafeCode() == null ? "none" : value(completion.getSafeCode());
            if (observation != null) {
                observation.lowCardinalityKeyValue("outcome", outcome)
                        .lowCardinalityKeyValue("safe.code", safeCode)
                        .highCardinalityKeyValue("key.success.count", Integer.toString(completion.getKeySuccessCount()))
                        .highCardinalityKeyValue("key.failure.count", Integer.toString(completion.getKeyFailureCount()));
            }
            if (metricsEnabled) {
                String family = family(completion.getProviderFamily());
                Counter.builder("embabel.decision.calls.total")
                        .tag("provider.family", family)
                        .tag("outcome", outcome)
                        .tag("safe.code", safeCode)
                        .register(meterRegistry)
                        .increment();
                Timer.builder("embabel.decision.duration")
                        .tag("provider.family", family)
                        .tag("outcome", outcome)
                        .tag("safe.code", safeCode)
                        .register(meterRegistry)
                        .record(completion.getElapsedNanos(), TimeUnit.NANOSECONDS);
                Counter.builder("embabel.decision.keys.total")
                        .tag("provider.family", family)
                        .tag("key.outcome", "success")
                        .register(meterRegistry)
                        .increment(completion.getKeySuccessCount());
                Counter.builder("embabel.decision.keys.total")
                        .tag("provider.family", family)
                        .tag("key.outcome", "failure")
                        .register(meterRegistry)
                        .increment(completion.getKeyFailureCount());
            }
        }

        @Override
        public void close() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            if (scope != null) {
                scope.close();
            }
            if (observation != null) {
                observation.stop();
            }
        }
    }

    private static String family(DecisionProviderFamily family) {
        return value(family);
    }

    private static String value(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

}
