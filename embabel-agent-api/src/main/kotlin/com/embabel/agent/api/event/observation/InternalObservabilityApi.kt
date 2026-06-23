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

/**
 * Marks observability instrumentation types (observation contexts, the [AgentInstrumentation] port
 * and its helpers) as internal SPI: the core uses them to create spans, but they are not part of the
 * public agent API and may change without notice. Kotlin callers must opt in with
 * `@OptIn(InternalObservabilityApi::class)`.
 *
 * Note: opt-in is enforced by the Kotlin compiler only; Java callers (e.g. the observability module)
 * are not checked.
 */
@RequiresOptIn(
    message = "Internal observability API. Use with caution as it may change without notice.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalObservabilityApi
