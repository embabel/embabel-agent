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
package com.embabel.common.ai.decision.systemone

import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.model.DecisionModelDescriptor
import com.embabel.common.ai.model.DecisionModelProvider
import com.embabel.common.ai.model.observation.ObservedDecisionService
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import java.time.LocalDate
import java.util.Collections
import java.util.function.Supplier
import java.util.concurrent.CancellationException

/** Optional provider policy. General SystemOne endpoints require no native-engine headers or identity. */
@ApiStatus.Experimental
interface SystemOneModelProfile {
    fun validateRequest(request: DecisionRequest) {}
    fun provenance(metadata: SystemOneResponseMetadata): ModelProvenance
    fun failureReason(statusCode: Int): FailureReason =
        if (statusCode == 400 || statusCode == 422) FailureReason.INVALID_RESPONSE else FailureReason.UNAVAILABLE
}

/** Bounded metadata for a profile, without a raw body or URL. Headers are copied and never printed. */
@ApiStatus.Experimental
class SystemOneResponseMetadata(
    val requestedModel: String,
    val actualModel: String,
    val inputTokens: Long,
    val outputTokens: Long,
    headers: Map<String, List<String>>,
) {
    val headers: Map<String, List<String>> = Collections.unmodifiableMap(
        headers.mapValues { (_, values) -> java.util.List.copyOf(values) }.toMap(LinkedHashMap()),
    )
    init {
        require(validModelName(requestedModel) && validModelName(actualModel) && inputTokens >= 0 && outputTokens >= 0) {
            "Invalid SystemOne response metadata"
        }
        require(headerBytes(this.headers) <= 8192) { "SystemOne response metadata headers exceed the limit" }
    }
    override fun toString(): String = "SystemOneResponseMetadata(inputTokens=$inputTokens, outputTokens=$outputTokens)"
}

/**
 * Creates local-catalog services without HTTP or credential resolution. [discoverModels] is the
 * explicit remote catalog operation and does not alter this factory's configured model names.
 */
@ApiStatus.Experimental
class SystemOneModelFactory @JvmOverloads constructor(
    override val id: String,
    private val providerName: String,
    options: SystemOneClientOptions,
    modelNames: List<String>,
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    tokenSupplier: Supplier<String?> = Supplier { null },
    profile: SystemOneModelProfile? = null,
) : DecisionModelProvider, AutoCloseable {
    private val names = java.util.List.copyOf(modelNames)
    private val profile = profile ?: object : SystemOneModelProfile {
        override fun provenance(metadata: SystemOneResponseMetadata): ModelProvenance {
            val requestId = metadata.headers["x-typesafe-request-id"]?.singleOrNull()
                ?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }
            return ModelProvenance(metadata.actualModel, providerName, requestId = requestId)
        }
    }
    private val client: SystemOneClient
    init {
        require(id.isNotBlank() && validModelName(providerName)) { "SystemOne provider id and name must be nonblank" }
        require(names.all(::validModelName) && names.size == names.toSet().size) { "SystemOne model names must be valid and unique" }
        client = SystemOneClient(options, tokenSupplier, this.profile)
    }
    override fun models(): List<DecisionModelDescriptor> = java.util.List.copyOf(names.map(::describe))
    override fun describe(modelName: String): DecisionModelDescriptor {
        require(modelName in names) { "Unknown SystemOne model in the local catalog" }
        return DecisionModelDescriptor(modelName, SystemOneDecisionService.CAPABILITIES)
    }
    override fun create(modelName: String): DecisionService {
        describe(modelName)
        return ObservedDecisionService(SystemOneDecisionService(modelName, providerName, client, profile), observationRegistry)
    }
    fun discoverModels(): List<DecisionModelDescriptor> = try {
        val response = client.exchange("models", null)
        val root = SystemOneCodec.read(response.body)
        require(root.isObject && root.get("models")?.isArray == true) { "Invalid catalog" }
        val models = root.get("models").values().map { model ->
            val name = SystemOneCodec.text(model, "name")
            require(validModelName(name)) { "Invalid catalog model name" }
            SystemOneCodec.text(model, "description")
            LocalDate.parse(SystemOneCodec.text(model, "release_date"))
            DecisionModelDescriptor(name, SystemOneDecisionService.CAPABILITIES)
        }
        require(models.map { it.modelName }.distinct().size == models.size) { "Duplicate catalog model names" }
        java.util.List.copyOf(models)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw IllegalStateException("SystemOne model discovery failed")
    }
    override fun close() { client.close() }
    override fun toString(): String = "SystemOneModelFactory(models=${names.size})"
}

internal fun validModelName(value: String): Boolean = value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() }
internal fun headerBytes(headers: Map<String, List<String>>): Long = headers.entries.sumOf { (name, values) ->
    values.sumOf { value -> name.toByteArray(Charsets.UTF_8).size.toLong() + value.toByteArray(Charsets.UTF_8).size + 4 }
}
