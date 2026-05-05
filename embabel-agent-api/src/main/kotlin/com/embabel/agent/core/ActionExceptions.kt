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
 * Marker interface for exceptions that should trigger action retry.
 *
 * Exceptions implementing this interface are considered transient/recoverable
 * and will be retried according to the action's QoS retry policy.
 *
 * Use this for temporary failures that may succeed on retry:
 * - Network timeouts
 * - Temporary service unavailability
 * - Rate limiting
 * - Transient database connection errors
 *
 * Example:
 * ```kotlin
 * class TemporaryApiFailureException(message: String)
 *     : RuntimeException(message), RetryableException
 * ```
 *
 * @see NonRetryableException
 * @see ActionException
 */
interface RetryableException

/**
 * Marker interface for exceptions that should NOT trigger action retry.
 *
 * Exceptions implementing this interface are considered permanent/non-recoverable
 * and will cause the action to fail immediately without retry attempts.
 *
 * Use this for deterministic failures that won't change on retry:
 * - Validation errors
 * - Business rule violations
 * - Invalid input that won't change on retry
 * - Resource not found errors (404)
 * - Authentication failures (401)
 *
 * Example:
 * ```kotlin
 * class InvalidInputException(message: String)
 *     : RuntimeException(message), NonRetryableException
 * ```
 *
 * @see RetryableException
 * @see ActionException
 */
interface NonRetryableException

/**
 * Base class for action execution exceptions with built-in retry classification.
 *
 * Provides convenient base classes for retryable and non-retryable exceptions
 * without requiring users to implement marker interfaces directly.
 *
 * Example usage:
 * ```java
 * @Action
 * public Result processOrder(Order order) {
 *     if (!order.isValid()) {
 *         // Won't be retried
 *         throw new ActionException.NonRetryable("Invalid order: " + order.id);
 *     }
 *
 *     try {
 *         return externalApi.submit(order);
 *     } catch (TimeoutException e) {
 *         // Will be retried
 *         throw new ActionException.Retryable("API timeout", e);
 *     }
 * }
 * ```
 *
 * @see RetryableException
 * @see NonRetryableException
 */
sealed class ActionException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    /**
     * Transient failure that can be retried.
     *
     * Use for temporary failures like:
     * - Network timeouts
     * - Rate limiting (429 errors)
     * - Temporary resource unavailability
     * - Connection failures
     * - Database deadlocks
     *
     * @param message Human-readable error description
     * @param cause Optional underlying exception
     */
    class Retryable(message: String, cause: Throwable? = null)
        : ActionException(message, cause), RetryableException

    /**
     * Permanent failure that should not be retried.
     *
     * Use for deterministic failures like:
     * - Validation errors
     * - Business rule violations
     * - Invalid parameters
     * - Resource not found (404 errors)
     * - Authentication failures (401 errors)
     * - Authorization failures (403 errors)
     *
     * @param message Human-readable error description
     * @param cause Optional underlying exception
     */
    class NonRetryable(message: String, cause: Throwable? = null)
        : ActionException(message, cause), NonRetryableException
}
