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
import io.micrometer.context.ContextRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Registers {@link MdcThreadLocalAccessor} on the global {@link ContextRegistry} so the Embabel MDC
 * correlation keys cross thread boundaries when work is dispatched through {@code ExecutorAsyncer}
 * (which captures via {@code ContextSnapshotFactory} on the global registry).
 *
 * <p><b>Why this is needed.</b> {@code MdcPropagationEventListener} sets {@code run_id}/{@code
 * agent.name} on the thread that receives {@code AgentProcessCreationEvent} (the caller) and {@code
 * action.name} on the thread running the action (an async worker). MDC is a {@link ThreadLocal}, and
 * Spring Boot's context propagation carries only the current observation/span — never MDC. So across
 * an async hop the worker thread starts with none of the keys, and log lines show them empty. This
 * accessor lets the keys ride the same snapshot the spans already use, restoring correlation on
 * worker threads. It works independently of tracing (no {@code Tracer} required).
 *
 * <p>Registration is idempotent (any prior accessor under the same key is removed first) and undone
 * on {@link #destroy()} to avoid leaking across application contexts — the {@link ContextRegistry} is
 * a process-wide singleton.
 *
 * @since 0.3.4
 */
public class EmbabelMdcContextPropagationRegistrar implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EmbabelMdcContextPropagationRegistrar.class);

    private final MdcThreadLocalAccessor accessor = new MdcThreadLocalAccessor();

    @Override
    public void afterPropertiesSet() {
        // Idempotent: drop any stale accessor under the same key (e.g. a previous context in tests).
        ContextRegistry.getInstance().removeThreadLocalAccessor(MdcThreadLocalAccessor.KEY);
        ContextRegistry.getInstance().registerThreadLocalAccessor(accessor);
        log.debug("Registered MdcThreadLocalAccessor (key={}) for cross-thread MDC correlation",
                MdcThreadLocalAccessor.KEY);
    }

    @Override
    public void destroy() {
        ContextRegistry.getInstance().removeThreadLocalAccessor(MdcThreadLocalAccessor.KEY);
        log.debug("Removed MdcThreadLocalAccessor (key={})", MdcThreadLocalAccessor.KEY);
    }
}
