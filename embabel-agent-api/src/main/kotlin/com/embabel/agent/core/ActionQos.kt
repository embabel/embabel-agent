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

import com.embabel.agent.spi.common.RetryProperties

/**
 * Quality of service requirements for an action
 */
data class ActionQos(
    override val maxAttempts: Int = 5,
    override val backoffMillis: Long = 10000,
    override val backoffMultiplier: Double = 5.0,
    override val backoffMaxInterval: Long = 60000,
    val idempotent: Boolean = false,
) : RetryProperties
