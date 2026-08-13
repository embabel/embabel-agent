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
package com.embabel.agent.api.common

import java.util.concurrent.CompletableFuture

/**
 * Simple Java-friendly async interface.
 *
 * An implementation is a supported extension point, and one obligation comes with it: **capture
 * the calling thread's context and re-establish it on the worker**. Three things travel that way
 * today - the [com.embabel.agent.core.AgentProcess], the current Micrometer observation, and the
 * [com.embabel.common.ai.model.ModelSelectionContext] - and
 * [com.embabel.agent.spi.support.ExecutorAsyncer] is the reference for how.
 *
 * On the way out, restore what the worker held before rather than clearing. It matters only when
 * the worker IS the submitting thread, which an executor is free to arrange - a direct executor
 * always does, and a bounded pool with `CallerRunsPolicy` does once saturated. The model selection
 * context does this already. `AgentProcess` still clears, which is #1911 and is fixed in #1912;
 * this doc states the obligation for new implementations either way.
 *
 * Dropping them does not fail loudly, which is what makes this worth stating. A lost model
 * selection context means role resolution quietly falls back to deployment configuration and
 * serves a model the deployment is billed for, on a call the user brought their own key for. A
 * lost `AgentProcess` breaks the platform's own bookkeeping just as silently.
 */
interface Asyncer {

    fun <T> async(block: () -> T): CompletableFuture<T>

    fun <T, R> parallelMap(
        items: Collection<T>,
        maxConcurrency: Int,
        transform: (t: T) -> R,
    ): List<R>

}
