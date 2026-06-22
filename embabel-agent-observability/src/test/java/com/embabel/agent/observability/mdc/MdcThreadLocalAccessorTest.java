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
package com.embabel.agent.observability.mdc;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MdcThreadLocalAccessor}.
 *
 * <p>The accessor lets the Embabel MDC keys ({@code run_id}, {@code agent.name}, {@code action.name})
 * ride along the {@link ContextSnapshot} that {@code ExecutorAsyncer} captures when it dispatches work
 * to a worker thread — so a log line emitted on the worker carries the same correlation keys as the
 * calling thread. Without it, MDC (a {@link ThreadLocal}) does not cross the async boundary and the
 * keys show up empty on the worker.
 *
 * <p>Crucially the accessor scopes to <em>only</em> the Embabel keys, so unrelated application MDC is
 * neither propagated nor clobbered.
 */
class MdcThreadLocalAccessorTest {

    private static final String RUN_ID = "embabel.agent.run_id";
    private static final String AGENT_NAME = "embabel.agent.name";
    private static final String ACTION_NAME = "embabel.action.name";
    private static final String APP_KEY = "app.requestId";

    private final MdcThreadLocalAccessor accessor = new MdcThreadLocalAccessor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    @DisplayName("Accessor semantics")
    class Semantics {

        @Test
        @DisplayName("key() is the stable accessor key")
        void keyIsStable() {
            assertThat(accessor.key()).isEqualTo(MdcThreadLocalAccessor.KEY);
        }

        @Test
        @DisplayName("getValue() returns null when no Embabel MDC keys are set")
        void getValueNullWhenEmpty() {
            assertThat(accessor.getValue()).isNull();
        }

        @Test
        @DisplayName("getValue() captures only Embabel keys, ignoring application MDC")
        void getValueCapturesOnlyEmbabelKeys() {
            MDC.put(RUN_ID, "run-1");
            MDC.put(AGENT_NAME, "MyAgent");
            MDC.put(APP_KEY, "req-99");

            Map<String, String> value = accessor.getValue();

            assertThat(value).containsOnly(
                    Map.entry(RUN_ID, "run-1"),
                    Map.entry(AGENT_NAME, "MyAgent"));
            assertThat(value).doesNotContainKey(APP_KEY);
        }

        @Test
        @DisplayName("setValue(map) sets the Embabel keys on the current thread")
        void setValuePutsKeys() {
            accessor.setValue(Map.of(RUN_ID, "run-7", ACTION_NAME, "doThing"));

            assertThat(MDC.get(RUN_ID)).isEqualTo("run-7");
            assertThat(MDC.get(ACTION_NAME)).isEqualTo("doThing");
        }

        @Test
        @DisplayName("setValue(map) replaces stale Embabel keys not present in the new value")
        void setValueReplacesStaleKeys() {
            MDC.put(ACTION_NAME, "oldAction");

            accessor.setValue(Map.of(RUN_ID, "run-7"));

            assertThat(MDC.get(RUN_ID)).isEqualTo("run-7");
            assertThat(MDC.get(ACTION_NAME)).as("stale Embabel key cleared").isNull();
        }

        @Test
        @DisplayName("setValue() / reset clears only Embabel keys, leaving application MDC intact")
        void resetClearsOnlyEmbabelKeys() {
            MDC.put(RUN_ID, "run-1");
            MDC.put(AGENT_NAME, "MyAgent");
            MDC.put(APP_KEY, "req-99");

            accessor.setValue();

            assertThat(MDC.get(RUN_ID)).isNull();
            assertThat(MDC.get(AGENT_NAME)).isNull();
            assertThat(MDC.get(APP_KEY)).as("application MDC untouched").isEqualTo("req-99");
        }
    }

    @Nested
    @DisplayName("Cross-thread propagation via ContextSnapshot")
    class CrossThread {

        @Test
        @DisplayName("Embabel MDC rides the snapshot to a worker thread; application MDC does not")
        void embabelMdcPropagatesAppMdcDoesNot() throws Exception {
            ContextRegistry registry = new ContextRegistry();
            registry.registerThreadLocalAccessor(accessor);
            ContextSnapshotFactory factory = ContextSnapshotFactory.builder()
                    .contextRegistry(registry)
                    .clearMissing(true)
                    .build();

            MDC.put(RUN_ID, "run-cross");
            MDC.put(AGENT_NAME, "CrossAgent");
            MDC.put(ACTION_NAME, "crossAction");
            MDC.put(APP_KEY, "req-cross");

            ContextSnapshot snapshot = factory.captureAll();

            AtomicReference<String> workerRunId = new AtomicReference<>();
            AtomicReference<String> workerAgent = new AtomicReference<>();
            AtomicReference<String> workerAction = new AtomicReference<>();
            AtomicReference<String> workerAppKey = new AtomicReference<>();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                        workerRunId.set(MDC.get(RUN_ID));
                        workerAgent.set(MDC.get(AGENT_NAME));
                        workerAction.set(MDC.get(ACTION_NAME));
                        workerAppKey.set(MDC.get(APP_KEY));
                    }
                }).get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            assertThat(workerRunId.get()).isEqualTo("run-cross");
            assertThat(workerAgent.get()).isEqualTo("CrossAgent");
            assertThat(workerAction.get()).isEqualTo("crossAction");
            assertThat(workerAppKey.get())
                    .as("application MDC is not captured by the Embabel accessor")
                    .isNull();
        }

        @Test
        @DisplayName("worker MDC is restored to its prior state when the scope closes")
        void workerMdcRestoredAfterScope() throws Exception {
            ContextRegistry registry = new ContextRegistry();
            registry.registerThreadLocalAccessor(accessor);
            ContextSnapshotFactory factory = ContextSnapshotFactory.builder()
                    .contextRegistry(registry)
                    .clearMissing(true)
                    .build();

            MDC.put(RUN_ID, "caller-run");
            ContextSnapshot snapshot = factory.captureAll();

            AtomicReference<String> insideScope = new AtomicReference<>();
            AtomicReference<String> afterScope = new AtomicReference<>();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    // Worker starts with its own pre-existing Embabel key.
                    MDC.put(RUN_ID, "worker-own");
                    try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                        insideScope.set(MDC.get(RUN_ID));
                    }
                    afterScope.set(MDC.get(RUN_ID));
                }).get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            assertThat(insideScope.get()).as("snapshot value applied inside the scope").isEqualTo("caller-run");
            assertThat(afterScope.get()).as("worker's own value restored after scope").isEqualTo("worker-own");
        }
    }
}
