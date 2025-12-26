/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.common.ai.autoconfig

import java.time.Instant

/**
 * Result of LLM initialization process.
 */
data class ProviderInitialization(
    val provider: String,
    val registeredLlms: List<RegisteredModel>,
    val registeredEmbeddings: List<RegisteredModel> = emptyList(),
    val initializedAt: Instant = Instant.now()
) {
    val totalLlms: Int get() = registeredLlms.size
    val totalEmbeddings: Int get() = registeredEmbeddings.size

    fun summary(): String =
        "$provider: Initialized $totalLlms LLM(s) and $totalEmbeddings embedding(s)"
}

/**
 * Represents a registered model with its bean name and model ID.
 */
data class RegisteredModel(
    val beanName: String,
    val modelId: String
)
