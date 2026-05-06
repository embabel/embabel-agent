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
package com.embabel.agent.core

/**
 * Marker interface indicating that an operation should be retried on failure.
 *
 * Can be applied to exceptions, commands, actions, or any operation that may
 * fail transiently and should be retried according to the configured retry policy.
 *
 * Common use cases:
 * - Network timeouts
 * - Temporary service unavailability
 * - Rate limiting (429 errors)
 * - Transient database connection errors
 *
 * Example usage:
 * ```kotlin
 * import com.embabel.agent.core.Retryable
 *
 * class ApiTimeoutException(message: String)
 *     : RuntimeException(message), Retryable
 * ```
 *
 * @see NonRetryable
 * @see ActionException
 */
interface Retryable

/**
 * Marker interface indicating that an operation should NOT be retried on failure.
 *
 * Can be applied to exceptions, commands, actions, or any operation that fails
 * deterministically and will not succeed on retry.
 *
 * Common use cases:
 * - Validation errors
 * - Business rule violations
 * - Invalid input parameters
 * - Resource not found (404 errors)
 * - Authentication failures (401 errors)
 * - Authorization failures (403 errors)
 *
 * Example usage:
 * ```kotlin
 * import com.embabel.agent.core.NonRetryable
 *
 * class ValidationException(message: String)
 *     : RuntimeException(message), NonRetryable
 * ```
 *
 * @see Retryable
 * @see ActionException
 */
interface NonRetryable
