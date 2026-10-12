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
package com.embabel.agent.config.platform

import org.jetbrains.annotations.ApiStatus
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ApiStatus.Internal
@ConfigurationProperties("embabel.agent.platform.models.systemone", ignoreUnknownFields = false)
class SystemOneDecisionModelsProperties(
    val enabled: Boolean = false,
    val providerId: String = "systemone",
    val providerName: String = "SystemOne",
    val baseUrl: String? = null,
    val modelNames: List<String> = emptyList(),
    val timeout: Duration = Duration.ofSeconds(45),
    val maxRequestBytes: Int = 16384,
    val maxResponseBytes: Int = 65536,
    val apiKey: String? = null,
) {
    override fun toString(): String = "SystemOneDecisionModelsProperties(enabled=$enabled)"
}
