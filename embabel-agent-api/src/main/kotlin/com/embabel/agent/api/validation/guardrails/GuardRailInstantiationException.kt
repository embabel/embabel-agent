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
package com.embabel.agent.api.validation.guardrails

/**
 * Exception thrown when a guardrail fails to instantiate during application startup.
 *
 * This exception is thrown only when `embabel.agent.guardrails.fail-on-error=true`.
 * It indicates a configuration error such as:
 * - Invalid class name
 * - Class does not implement the required guardrail interface
 * - Constructor instantiation failure
 */
class GuardRailInstantiationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
