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
package com.embabel.agent.spi.decision

import com.embabel.agent.spi.common.RetryProperties

/** Retry settings for tests, with waits short enough not to slow them down. */
class QuickRetry @JvmOverloads constructor(
    override val maxAttempts: Int = 3,
    override val backoffMillis: Long = 1L,
    override val backoffMultiplier: Double = 2.0,
    override val backoffMaxInterval: Long = 2L,
) : RetryProperties {
    override val propertyPrefix = "embabel.agent.platform.decisions.test"
}
