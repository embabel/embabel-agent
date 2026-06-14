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
package com.embabel.agent.observability.observation;

import com.embabel.agent.api.event.observation.AgentInstrumentation;
import com.embabel.agent.api.event.observation.Observations;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import kotlin.jvm.functions.Function0;

/**
 * Micrometer adapter for {@link AgentInstrumentation}: opens a real Micrometer observation (span) for
 * each core work site on the supplied registry, delegating to {@link Observations#observeOrSkip} (which
 * owns the open/close/error lifecycle, so no scope leaks — and short-circuits to a pure passthrough
 * when the registry is no-op).
 *
 * <p>This adapter is contributed only by the observability module's auto-configuration. When that module
 * is absent (or disabled), no {@link AgentInstrumentation} bean of this type exists and the core falls
 * back to {@code NoOpAgentInstrumentation} — so "no module = no embabel spans" is structural, not
 * flag-driven.
 */
public class MicrometerAgentInstrumentation implements AgentInstrumentation {

    private final ObservationRegistry registry;

    public MicrometerAgentInstrumentation(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <T> T observe(Function0<? extends Observation.Context> context, Function0<? extends T> work) {
        return Observations.observeOrSkip(registry, context, work);
    }
}
