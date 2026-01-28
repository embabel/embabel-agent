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
 * Retry policy selector for an action.
 *
 * This is the first-priority override for retry behavior on an {@link com.embabel.agent.api.annotation.Action}
 * or {@link com.embabel.agent.api.annotation.Agent}. The underlying policy maps to {@link ActionQos} with the
 * following default properties:
 * max-attempts: int = 5
 * backoff-millis: long = 10000
 * backoff-multiplier: double = 5.0
 * backoff-maxInterval: long = 60000
 * idempotent: boolean = false
 * To override with a custom policy, see actionRetryPolicyExpression on {@link com.embabel.agent.api.annotation.Action}
 * or {@link com.embabel.agent.api.annotation.Agent}.
 */
enum class ActionRetryPolicy {

    /**
     * Fire only once: maps to {@link ActionQos} with maxAttempts = 1.
     */
    FIRE_ONCE,

    /**
     * Default retry policy: uses the default {@link ActionQos}. Note that using this retry policy explicitly will not
     * override any custom retry policy provided at any level, even if that custom retry policy is at a lower
     * precedence than the one annotated with this retry policy.
     */
    DEFAULT

}
