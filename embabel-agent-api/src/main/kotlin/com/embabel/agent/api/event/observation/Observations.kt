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
     * Run [work] inside an `observe{}` span named [name], or run it directly with no observation
     * overhead when [registry] is a no-op. The span always closes — and is errored if [work]
     * throws — because [Observation.observe] owns the scope, so no scope can leak.
     */
    @JvmStatic
    fun <T> observeOrSkip(
        registry: ObservationRegistry,
        name: String,
        context: () -> Observation.Context,
        work: () -> T,
    ): T =
        if (registry.isNoop) {
            work()
        } else {
            Observation.createNotStarted(name, Supplier { context() }, registry).observe(Supplier { work() })!!
        }
}
