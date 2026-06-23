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

import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Micrometer {@link ThreadLocalAccessor} that carries the Embabel MDC correlation keys across
 * thread boundaries.
 *
 * <p>{@link MdcPropagationEventListener} sets {@code run_id}/{@code agent.name}/{@code action.name}
 * into SLF4J {@link MDC} as the agent runs. But MDC is a {@link ThreadLocal}, and the run loop,
 * tool loop and async fan-out hop between worker threads via {@code ExecutorAsyncer}, which captures
 * a {@code ContextSnapshot} from the global registry. Registering this accessor on that registry lets
 * the Embabel MDC keys ride the snapshot, so a log line emitted on a worker thread carries the same
 * correlation keys as the thread that dispatched the work. Without it the keys show up empty on the
 * worker.
 *
 * <p>The accessor scopes strictly to the three Embabel keys: unrelated application MDC is neither
 * captured (so it does not leak onto pooled worker threads) nor cleared (so it survives a restore).
 *
 * @since 0.3.4
 */
public class MdcThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    /**
     * Stable key identifying this accessor on the {@link io.micrometer.context.ContextRegistry}.
     */
    public static final String KEY = "embabel.agent.mdc";

    private static final List<String> EMBABEL_KEYS = List.of(
            MdcPropagationEventListener.MDC_RUN_ID,
            MdcPropagationEventListener.MDC_AGENT_NAME,
            MdcPropagationEventListener.MDC_ACTION_NAME);

    @Override
    public Object key() {
        return KEY;
    }

    /**
     * @return the Embabel MDC keys currently set on this thread, or {@code null} if none are set
     * (so the snapshot records nothing to propagate when no agent context is active).
     */
    @Override
    public Map<String, String> getValue() {
        Map<String, String> value = null;
        for (String key : EMBABEL_KEYS) {
            String v = MDC.get(key);
            if (v != null) {
                if (value == null) {
                    value = new LinkedHashMap<>();
                }
                value.put(key, v);
            }
        }
        return value;
    }

    /**
     * Makes the Embabel MDC keys on this thread exactly match {@code value}: any stale Embabel key not
     * present in {@code value} is removed first, then the supplied entries are applied. Application MDC
     * keys are left untouched.
     */
    @Override
    public void setValue(Map<String, String> value) {
        removeEmbabelKeys();
        value.forEach(MDC::put);
    }

    /**
     * Clears the Embabel MDC keys on this thread (the empty/restore-to-absent case), leaving
     * application MDC keys untouched.
     */
    @Override
    public void setValue() {
        removeEmbabelKeys();
    }

    private static void removeEmbabelKeys() {
        EMBABEL_KEYS.forEach(MDC::remove);
    }
}
