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

import io.micrometer.context.ContextRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.contextpropagation.ObservationAwareSpanThreadLocalAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Registers {@link ObservationAwareSpanThreadLocalAccessor} on the global {@link ContextRegistry} so
 * the <em>current span</em> — not only the current observation — crosses thread boundaries when work
 * is dispatched through {@code ExecutorAsyncer} (which captures via {@code ContextSnapshotFactory} on
 * the global registry).
 *
 * <p><b>Why this is needed.</b> Spring Boot auto-registers only
 * {@code ObservationThreadLocalAccessor}, which propagates the current <em>observation</em>. When a
 * tier is suppressed by the tier filter (e.g. {@code trace-action=false}), that tier's observation is
 * a no-op carrying no span. The live ancestor span (e.g. the agent) is then reachable only via
 * {@code tracer.currentSpan()} — a thread local that is <em>not</em> propagated by the observation
 * accessor. Across an async hop the child (e.g. the LLM span) therefore loses its parent and starts a
 * brand-new root trace. {@link ObservationAwareSpanThreadLocalAccessor#getValue()} falls back to
 * {@code tracer.currentSpan()} precisely in this case, so registering it lets the live span travel to
 * the worker thread and the child re-parents correctly.
 *
 * <p>Per Micrometer's contract this accessor must be registered <b>after</b>
 * {@code ObservationThreadLocalAccessor}; Boot/the ServiceLoader register that one first, so adding
 * this one here satisfies the ordering. Registration is idempotent (any prior accessor under the same
 * key is removed first) and undone on {@link #destroy()} to avoid leaking across application contexts
 * — the {@link ContextRegistry} is a process-wide singleton.
 *
 * @since 0.3.4
 */
public class EmbabelTracingContextPropagationRegistrar implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EmbabelTracingContextPropagationRegistrar.class);

    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private ObservationAwareSpanThreadLocalAccessor accessor;

    /**
     * @param tracer the tracer, or {@code null} when no tracing bridge is present — the registrar
     *               then no-ops (there is no current span to propagate).
     */
    public EmbabelTracingContextPropagationRegistrar(ObservationRegistry observationRegistry, Tracer tracer) {
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;
    }

    @Override
    public void afterPropertiesSet() {
        if (tracer == null) {
            log.debug("No Tracer present; skipping span context-propagation accessor registration");
            return;
        }
        accessor = new ObservationAwareSpanThreadLocalAccessor(observationRegistry, tracer);
        // Idempotent: drop any stale accessor under the same key (e.g. a previous context in tests).
        ContextRegistry.getInstance().removeThreadLocalAccessor(accessor.key());
        ContextRegistry.getInstance().registerThreadLocalAccessor(accessor);
        log.debug("Registered ObservationAwareSpanThreadLocalAccessor (key={}) for cross-thread span propagation",
                accessor.key());
    }

    @Override
    public void destroy() {
        if (accessor != null) {
            ContextRegistry.getInstance().removeThreadLocalAccessor(accessor.key());
            log.debug("Removed ObservationAwareSpanThreadLocalAccessor (key={})", accessor.key());
        }
    }
}
