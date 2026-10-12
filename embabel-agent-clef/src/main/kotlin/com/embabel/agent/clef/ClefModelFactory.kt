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
package com.embabel.agent.clef

import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.systemone.SystemOneModelFactory
import com.embabel.common.ai.model.DecisionModelDescriptor
import com.embabel.common.ai.model.DecisionModelProvider
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import java.util.function.Supplier

/** Local catalog and typed services over the shared SystemOne client; never starts or provisions native code. */
@ApiStatus.Experimental
class ClefModelFactory @JvmOverloads constructor(
    options: ClefClientOptions,
    observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) : DecisionModelProvider, AutoCloseable {
    private val delegate = SystemOneModelFactory(ID, PROVIDER, options.sharedOptions(), listOf(MODEL),
        observationRegistry, Supplier { null }, ClefProtocol())
    override val id: String get() = ID
    override fun models(): List<DecisionModelDescriptor> = delegate.models()
    override fun describe(modelName: String): DecisionModelDescriptor = delegate.describe(modelName)
    override fun create(modelName: String): DecisionService = delegate.create(modelName)
    override fun close() { delegate.close() }
    override fun toString(): String = "ClefModelFactory(model=$MODEL)"
    companion object {
        const val ID = "clef"
        const val PROVIDER = "CLEF"
        const val MODEL = "clef-text-0.6b-coreml"
    }
}
