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
package com.embabel.agent.decision.api

import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.Callable

/**
 * Carries platform-owned execution context from the thread calling [DecisionModel.ask] into
 * provider work without coupling the decision core to a context framework.
 *
 * [wrap] runs on the calling thread. Implementations capture there and return a callable that
 * establishes the captured context, invokes [work] inline at most once, and restores the
 * executing thread's previous context in `finally`. They must preserve the work's value or
 * failure. Throwing while wrapping or invoking fails the decision call closed.
 */
@ApiStatus.Experimental
interface DecisionExecutionContext {
    fun <T> wrap(work: Callable<T>): Callable<T>
}

internal object IdentityDecisionExecutionContext : DecisionExecutionContext {
    override fun <T> wrap(work: Callable<T>): Callable<T> = work
}
