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
package com.embabel.agent.spi.common

import java.time.Duration

/**
 * Connect and read timeouts for a model provider's HTTP clients.
 *
 * Every provider's properties class extends this and binds it under its own prefix, so each
 * provider is configured the same way, as `connect-timeout` and `read-timeout` under
 * `embabel.agent.platform.models.<provider>`. What an unset value means depends on how the provider
 * talks to its API, and each provider's properties class says which.
 */
abstract class ClientTimeoutProperties {

    /**
     * How long to wait to connect to the provider. Unset uses the provider's default.
     */
    open var connectTimeout: Duration? = null

    /**
     * How long to wait for the provider's response. Unset uses the provider's default.
     */
    open var readTimeout: Duration? = null
}
