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
package com.embabel.common.ai.decision.cloudflare

import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.systemone.SystemOneClient
import com.embabel.common.ai.decision.systemone.SystemOneDecisionService
import com.embabel.common.ai.decision.systemone.SystemOneHttpResponse
import com.embabel.common.ai.model.DecisionModelDescriptor
import com.embabel.common.ai.model.DecisionModelProvider
import com.embabel.common.ai.model.observation.ObservedDecisionService
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import java.net.URI
import java.util.function.Supplier

/** Declared text model catalog; construction and catalog operations perform no network or credential work. */
@ApiStatus.Experimental
class CloudflareModelFactory internal constructor(
    private val options: CloudflareClientOptions,
    modelNames: List<String>,
    private val observationRegistry: ObservationRegistry,
    tokenSupplier: Supplier<String?>,
    private val exchange: (SystemOneClient, URI, ByteArray) -> SystemOneHttpResponse,
) : DecisionModelProvider, AutoCloseable {
    @JvmOverloads
    constructor(
        options: CloudflareClientOptions,
        modelNames: List<String> = SUPPORTED_MODELS,
        observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
        tokenSupplier: Supplier<String?> = Supplier { null },
    ) : this(options, modelNames, observationRegistry, tokenSupplier, { client, target, body -> client.exchange(target, body) })

    override val id: String = "cloudflare"
    private val names = java.util.List.copyOf(modelNames)
    private val client: SystemOneClient
    init {
        require(names.all { it in SUPPORTED_MODELS } && names.distinct().size == names.size) {
            "Cloudflare model names must be supported and unique"
        }
        client = SystemOneClient(options.transportOptions(), tokenSupplier, CloudflareProfile, requireToken = true)
    }
    override fun models(): List<DecisionModelDescriptor> = java.util.List.copyOf(names.map(::describe))
    override fun describe(modelName: String): DecisionModelDescriptor {
        require(modelName in names) { "Unknown Cloudflare model in the declared catalog" }
        return DecisionModelDescriptor(modelName, SystemOneDecisionService.CAPABILITIES)
    }
    override fun create(modelName: String): DecisionService {
        describe(modelName)
        return ObservedDecisionService(CloudflareDecisionService(modelName, options) { target, body ->
            exchange(client, target, body)
        }, observationRegistry)
    }
    override fun close() { client.close() }
    override fun toString(): String = "CloudflareModelFactory(models=${names.size})"
    companion object {
        @JvmField
        val SUPPORTED_MODELS: List<String> = java.util.List.of("@cf/cloudflare/clef", "@cf/cloudflare/clef-flash")
        internal const val PROVIDER_NAME = "Cloudflare Workers AI"
    }
}
