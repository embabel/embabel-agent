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
 * Base class for action execution exceptions with built-in retry classification.
 *
 * Provides convenient base classes for retryable and non-retryable exceptions
 * without requiring users to implement marker interfaces directly.
 *
 * This class is open to allow users to create domain-specific exception hierarchies:
 * ```kotlin
 * import com.embabel.agent.core.ActionException
 *
 * // Extend ActionException for domain-specific exceptions
 * class ValidationException(message: String)
 *     : ActionException.Permanent(message)
 *
 * class ApiTimeoutException(message: String, cause: Throwable? = null)
 *     : ActionException.Transient(message, cause)
 *
 * // Or implement marker interfaces directly
 * import com.embabel.agent.core.NonRetryable
 * import com.embabel.agent.core.Retryable
 *
 * class CustomValidationError(message: String)
 *     : RuntimeException(message), NonRetryable
 *
 * class CustomTimeoutError(message: String)
 *     : RuntimeException(message), Retryable
 * ```
 *
 * Example usage:
 * ```java
 * import com.embabel.agent.core.ActionException;
 *
 * @Action
 * public Result processOrder(Order order) {
 *     if (!order.isValid()) {
 *         // Won't be retried
 *         throw new ActionException.Permanent("Invalid order: " + order.id);
 *     }
 *
 *     try {
 *         return externalApi.submit(order);
 *     } catch (TimeoutException e) {
 *         // Will be retried
 *         throw new ActionException.Transient("API timeout", e);
 *     }
 * }
 * ```
 *
 * @see Retryable
 * @see NonRetryable
 */
open class ActionException(
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
    open class Transient(message: String, cause: Throwable? = null)
        : ActionException(message, cause), Retryable

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
    open class Permanent(message: String, cause: Throwable? = null)
        : ActionException(message, cause), NonRetryable
}
