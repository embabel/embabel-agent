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
package com.embabel.agent.config.models.byok

/**
 * A provider's `connect-timeout` or `read-timeout` is set but is not a duration.
 *
 * Thrown when a per-user key first builds a service for that provider, not at startup, because
 * BYOK binds a provider's timeouts only when it first sees the provider. [property] is the full
 * property name, so the fix is to correct or remove that one value.
 */
class InvalidProviderTimeoutException(
    val property: String,
    cause: Throwable,
) : IllegalStateException("'$property' is not a valid duration, for example 90s or 5m", cause)
