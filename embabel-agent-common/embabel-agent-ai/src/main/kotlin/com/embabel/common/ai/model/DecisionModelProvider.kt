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
package com.embabel.common.ai.model

import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionService
import org.jetbrains.annotations.ApiStatus

/**
 * Advertises and constructs decision models without exposing provider-native clients.
 * [models] is a known local catalog, not exhaustive remote discovery. Descriptors describe what a
 * factory can construct; they do not verify remote availability or calibrated evidence.
 */
@ApiStatus.Experimental
interface DecisionModelProvider {
    val id: String
    fun models(): List<DecisionModelDescriptor> = emptyList()
    fun describe(modelName: String): DecisionModelDescriptor
    fun create(modelName: String): DecisionService
}

/** Construction metadata for one model; service provenance retains the provider's original labels. */
@ApiStatus.Experimental
data class DecisionModelDescriptor(val modelName: String, val capabilities: DecisionCapabilities) {
    init {
        require(modelName.isNotBlank()) { "Decision model name must not be blank" }
    }
}
