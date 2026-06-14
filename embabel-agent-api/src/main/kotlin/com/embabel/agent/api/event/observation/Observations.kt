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
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import java.util.function.Supplier

/**
 * Direct-instrumentation helpers used at the agent work sites.
 */
@ApiStatus.Internal
object Observations {

    /**
     * Neutral name handed to [Observation.createNotStarted]. The semantic span name is owned by the
     * registered [io.micrometer.observation.ObservationConvention] (in the observability module),
     * whose `getName()` overrides this at span start — so the core never hard-codes a telemetry
     * name. This placeholder is only ever visible in the rare fallback where a real (non-no-op)
     * registry is present without the embabel conventions.
     */
    const val PLACEHOLDER_NAME = "embabel.operation"

    /**
     * Run [work] inside an `observe{}` span carrying [context]. The span always closes — and is
     * errored if [work] throws — because [Observation.observe] owns the scope, so no scope can leak.
     *
     * No explicit no-op short-circuit is needed: [Observation.createNotStarted] already fast-returns
     * a no-op observation for a no-op [registry] without invoking the [context] supplier, so the
     * disabled-tracing path builds no context and runs [work] directly.
     *
     * The span name is not supplied here: the registered convention names it from [context] (see
     * [PLACEHOLDER_NAME]).
     *
     * Returns exactly what [work] returns, including `null`: [Observation.observe] is a transparent
     * wrapper that hands back `work()`'s own value, so this helper is safe for both nullable and
     * non-null [T]. The unchecked cast (rather than a `!!`) preserves a legitimate null result
     * instead of crashing on it.
     */
    @JvmStatic
    fun <T> observeOrSkip(
        registry: ObservationRegistry,
        context: () -> Observation.Context,
        work: () -> T,
    ): T {
        @Suppress("UNCHECKED_CAST")
        return Observation.createNotStarted(PLACEHOLDER_NAME, Supplier { context() }, registry)
            .observe(Supplier { work() }) as T
    }
}
