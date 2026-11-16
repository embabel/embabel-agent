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
package com.embabel.agent.a2a

import io.micrometer.context.ThreadLocalAccessor
import io.opentelemetry.context.Context
import java.util.ArrayDeque

/**
 * Carries the A2A session contextId across thread hops made through Micrometer context propagation.
 *
 * ## Why it is needed
 * The inbound contextId is stored in the request thread's OTel [Context] under [A2A_CONTEXT_ID_KEY],
 * which is bound to that thread only. Parallel actions and `parallelMap` run work on other threads
 * through [com.embabel.agent.spi.support.ExecutorAsyncer], which copies whatever
 * [io.micrometer.context.ContextSnapshotFactory.captureAll] captures: the observation and every
 * registered [ThreadLocalAccessor], but not an arbitrary OTel context key. Without this accessor an
 * A2A call made on such a worker thread finds no contextId, and the next agent starts a new session.
 *
 * ## Registration
 * Loaded by [io.micrometer.context.ContextRegistry.getInstance] through `ServiceLoader`
 * (`META-INF/services/io.micrometer.context.ThreadLocalAccessor`), so any code that propagates
 * context with the global registry carries the contextId, with no Spring configuration and no
 * change to the executor.
 *
 * ## Where the value lives on a worker thread
 * On the worker the contextId is held in this accessor's own per-thread stack, not in the OTel
 * [Context]. Opening OTel scopes here would make correctness depend on the order in which Micrometer
 * closes them relative to the tracing span's own OTel scope; closed out of order, a pooled thread
 * could keep a stale contextId. [currentContextId] reads both places, the OTel [Context] first.
 *
 * ## Lifecycle on the worker thread
 * - [setValue] with a value pushes it; [setValue] without a value (the caller had none) pushes "none".
 * - [restore] pops, ignoring the value Micrometer passes back. That value is what [getValue] saw
 *   before the task, which may have come from the OTel [Context]; writing it into the stack would
 *   leave it behind on the thread. Popping returns the stack to exactly its state before the task,
 *   so a pooled thread never keeps a contextId from an earlier task.
 *
 * The contextId never leaves the JVM through this class: it moves between threads only, never into
 * HTTP headers. See [A2A_CONTEXT_ID_KEY].
 */
class A2AContextIdThreadLocalAccessor : ThreadLocalAccessor<String> {

    override fun key(): Any = KEY

    override fun getValue(): String? = currentContextId()

    override fun setValue(value: String) {
        propagated.get().push(value)
    }

    override fun setValue() {
        propagated.get().push(NONE)
    }

    override fun restore(previousValue: String) = pop()

    override fun restore() = pop()

    companion object {

        /** Key under which this accessor is registered in the [io.micrometer.context.ContextRegistry]. */
        const val KEY = "embabel.a2a.context-id"

        /** Marks "the caller had no contextId"; [ArrayDeque] does not accept null. */
        private const val NONE = ""

        private val propagated: ThreadLocal<ArrayDeque<String>> = ThreadLocal.withInitial { ArrayDeque() }

        /**
         * The A2A contextId visible on the current thread: the one set by the A2A request handler in
         * the OTel [Context] on the request thread, otherwise the one propagated to this worker thread.
         */
        fun currentContextId(): String? =
            Context.current().get(A2A_CONTEXT_ID_KEY)
                ?: propagated.get().peekFirst()?.takeIf { it != NONE }

        private fun pop() {
            val stack = propagated.get()
            stack.pollFirst()
            if (stack.isEmpty()) propagated.remove()
        }
    }
}
