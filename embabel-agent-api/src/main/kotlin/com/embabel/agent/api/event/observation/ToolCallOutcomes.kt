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

import com.embabel.agent.api.event.ToolCallResponseEvent

/**
 * Java-friendly view of the Kotlin [Result] carried by [ToolCallResponseEvent], for Java consumers
 * (e.g. the observability module) that cannot read a Kotlin inline value class directly. Lives in
 * this Kotlin module so the read stays native — no reflection.
 */
@InternalObservabilityApi
object ToolCallOutcomes {

    /** The tool's textual result on success, or null if the call failed. */
    @JvmStatic
    fun resultText(event: ToolCallResponseEvent): String? = event.result.getOrNull()

    /** The throwable on failure, or null if the call succeeded. */
    @JvmStatic
    fun error(event: ToolCallResponseEvent): Throwable? = event.result.exceptionOrNull()
}
