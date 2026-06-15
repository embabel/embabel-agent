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

import com.embabel.agent.observability.mdc.MdcThreadLocalAccessor;
import com.embabel.agent.spi.support.ExecutorAsyncer;
import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the <em>wiring</em> of {@link EmbabelMdcContextPropagationRegistrar} — that the
 * auto-configuration creates the bean (gated by {@code mdc-propagation}) and that its lifecycle
 * registers/removes {@link MdcThreadLocalAccessor} on the global {@link ContextRegistry}, plus an
 * end-to-end proof that with the accessor registered the Embabel MDC keys actually cross an
 * {@link ExecutorAsyncer} thread hop (the bug this fixes: keys empty on worker threads).
 *
 * <p>The {@link ContextRegistry} is a process-wide singleton, so each test starts from a known
 * baseline (the accessor key removed) and cleans up afterwards to avoid leaking into other tests.
 */
class MdcContextPropagationWiringTest {

    private static final String MDC_ACCESSOR_KEY = MdcThreadLocalAccessor.KEY;
    private static final String RUN_ID = "embabel.agent.run_id";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void resetGlobalContextRegistry() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(MDC_ACCESSOR_KEY);
        MDC.clear();
    }

    private static boolean mdcAccessorRegistered() {
        return ContextRegistry.getInstance().getThreadLocalAccessors().stream()
                .anyMatch(a -> MDC_ACCESSOR_KEY.equals(a.key()));
    }

    @Test
    @DisplayName("default: registrar bean created, MDC accessor registered while live, removed on close")
    void registersMdcAccessorByDefaultAndRemovesOnClose() {
        assertThat(mdcAccessorRegistered()).as("baseline: accessor absent before context").isFalse();

        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(EmbabelMdcContextPropagationRegistrar.class);
            assertThat(mdcAccessorRegistered())
                    .as("accessor registered on the global ContextRegistry while context is live")
                    .isTrue();
        });

        assertThat(mdcAccessorRegistered())
                .as("accessor removed once the context closes (no global leak)")
                .isFalse();
    }

    @Test
    @DisplayName("mdc-propagation=false: no registrar bean, no accessor")
    void noRegistrarWhenMdcPropagationDisabled() {
        contextRunner
                .withPropertyValues("embabel.agent.platform.observability.mdc-propagation=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(EmbabelMdcContextPropagationRegistrar.class);
                    assertThat(mdcAccessorRegistered()).isFalse();
                });
    }

    @Test
    @DisplayName("end-to-end: with the registrar active, Embabel MDC crosses an ExecutorAsyncer hop")
    void embabelMdcCrossesExecutorAsyncerHop() {
        contextRunner.run(ctx -> {
            assertThat(mdcAccessorRegistered()).isTrue();

            var executor = Executors.newSingleThreadExecutor();
            try {
                ExecutorAsyncer asyncer = new ExecutorAsyncer(executor);
                MDC.put(RUN_ID, "run-async");

                AtomicReference<String> workerRunId = new AtomicReference<>();
                asyncer.async(() -> {
                    workerRunId.set(MDC.get(RUN_ID));
                    return null;
                }).join();

                assertThat(workerRunId.get())
                        .as("MDC run_id propagated to the ExecutorAsyncer worker thread")
                        .isEqualTo("run-async");
            } finally {
                executor.shutdownNow();
            }
        });
    }
}
