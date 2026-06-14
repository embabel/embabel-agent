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
package com.embabel.agent.api.event.observation

import io.micrometer.observation.Observation

/**
 * Port for direct instrumentation at the agent work sites (agent turn, action, LLM call, tool loop).
 *
 * The core depends only on this port; the actual span creation lives in an adapter supplied by the
 * observability module. When no module is present, [com.embabel.agent.api.common.PlatformServices.instrumentation]
 * defaults to [NoOpAgentInstrumentation], so the work runs un-observed and **no span is ever created** —
 * structurally, not via a runtime flag, and without the core ever reading an ambient
 * [io.micrometer.observation.ObservationRegistry].
 *
 * The span name is owned by the registered [io.micrometer.observation.ObservationConvention] in the
 * module, so it is the [Observation.Context] *type* — not a name — that drives naming. The adapter
 * owns the open/close (and error) lifecycle of the span around [observe], guaranteeing no scope leak.
 */
@InternalObservabilityApi
interface AgentInstrumentation {

    /**
     * Run [work] inside an observation built from [context]. Returns exactly what [work] returns,
     * including `null`, and propagates any exception unchanged.
     */
    fun <T> observe(context: () -> Observation.Context, work: () -> T): T
}

/**
 * No-op default: runs [work] and creates no observation, never even invoking [context]. Active
 * whenever no observability module contributes an [AgentInstrumentation] bean.
 */
@InternalObservabilityApi
object NoOpAgentInstrumentation : AgentInstrumentation {
    override fun <T> observe(context: () -> Observation.Context, work: () -> T): T = work()
}
