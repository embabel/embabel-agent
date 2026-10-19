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
package com.embabel.agent.systemone

import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.model.observation.ObservedDecisionService
import com.embabel.common.ai.decision.systemone.SystemOneCodec
import com.embabel.common.ai.decision.systemone.SystemOneFormatException
import com.embabel.common.byok.ByokFactory
import com.embabel.common.byok.InvalidApiKeyException
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.util.concurrent.CancellationException
import java.util.function.Supplier

/**
 * Builds decision services for the models on one System One endpoint. Every service the factory
 * builds shares one HTTP client and one key supplier.
 *
 * Building a service never resolves the key or contacts the endpoint. The key is read on each
 * call, so a rotated key takes effect on the next call. A blank key sends no Authorization
 * header, which suits endpoints that need none.
 *
 * When [restClientBuilder] is given, a clone of it is used, so its transport, interceptors and
 * timeouts apply. Otherwise the factory builds a client with the endpoint's timeouts that does
 * not follow redirects.
 *
 * @param endpoint where the models live
 * @param defaultModel the model [build] and [buildValidated] return
 * @param apiKey supplies the key for each call
 * @param restClientBuilder the application's HTTP client builder, or null for the factory's own
 * @param observationRegistry records decision and request observations
 */
@ApiStatus.Experimental
open class SystemOneModelFactory @JvmOverloads constructor(
    private val endpoint: SystemOneEndpoint,
    val defaultModel: String,
    private val apiKey: Supplier<String?>,
    restClientBuilder: RestClient.Builder? = null,
    observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
) : ByokFactory<DecisionService> {

    /** The registry that services built here record observations in. */
    protected val observationRegistry: ObservationRegistry = observationRegistry

    private val transport = SystemOneTransport(endpoint, apiKey, restClientBuilder, observationRegistry)

    init {
        require(defaultModel.isNotBlank()) { "The default model must not be blank" }
        logger.info(
            "System One model factory initialized: provider={}, transport={}, observations={}",
            endpoint.provider,
            transport.kind,
            if (observationRegistry.isNoop) "noop" else "enabled",
        )
    }

    /** Returns a service for the default model. */
    fun build(): DecisionService = build(defaultModel)

    /**
     * Returns a service for the named model. The name is sent as is, so the endpoint decides
     * whether it exists.
     *
     * @param model the model name
     * @return a service for that model
     */
    fun build(model: String): DecisionService {
        require(model.isNotBlank()) { "The model name must not be blank" }
        return ObservedDecisionService(SystemOneDecisionService(model, transport), observationRegistry)
    }

    /**
     * Lists the models the endpoint serves.
     *
     * An interrupted caller gets a [CancellationException]. That is a subclass of
     * [IllegalStateException], so catch it first if you catch [IllegalStateException], or the
     * interrupt is lost.
     *
     * @return the model names in the order the endpoint lists them
     * @throws IllegalStateException if the endpoint can't be reached or its listing can't be read
     * @throws CancellationException if the caller or key supplier is interrupted
     */
    fun modelNames(): List<String> = modelNames(apiKey)

    private fun modelNames(key: Supplier<String?>): List<String> =
        try {
            SystemOneCodec.modelNames(transport.listModels(key).body)
        } catch (failure: SystemOneFailure) {
            throw IllegalStateException(
                "${endpoint.provider} model listing failed: cause=${failure.category}, status=${failure.statusFamily}",
            )
        } catch (_: SystemOneFormatException) {
            throw IllegalStateException("${endpoint.provider} model listing could not be read")
        }

    /**
     * Reads the key once, checks it by listing the endpoint's models with it, then returns a service
     * for the default model. A missing key, a rejected listing or an unreachable endpoint all count
     * as an invalid key. A key supplier that throws [InvalidApiKeyException] itself, as one that
     * insists on a key does for a blank one, counts as a missing key. An already interrupted caller
     * is cancelled before the key is read, including when no key is configured. An interrupted key
     * supplier also throws a [CancellationException].
     *
     * @return a service for the default model
     * @throws InvalidApiKeyException if the key can't be validated
     * @throws CancellationException if the caller or key supplier is interrupted
     */
    override fun buildValidated(): DecisionService {
        val provider = endpoint.provider
        logger.debug("{} credential validation started", provider)
        val key = try {
            transport.resolveKey(::keyOrNull)
        } catch (failure: SystemOneFailure) {
            logger.warn("{} credential could not be resolved: cause={}", provider, failure.category)
            throw InvalidApiKeyException("$provider credential could not be validated")
        } ?: throw InvalidApiKeyException("$provider credential is missing")
        try {
            modelNames { key }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            logger.warn("{} credential validation failed: {}", provider, failure.message)
            throw InvalidApiKeyException("$provider credential could not be validated", failure)
        }
        logger.info("{} credential validation succeeded", provider)
        return build()
    }

    /**
     * Reads the key for validation, treating the supplier's own rejection of a blank key as no key.
     *
     * @return the key, or null when there is none
     */
    private fun keyOrNull(): String? =
        try {
            apiKey.get()
        } catch (_: InvalidApiKeyException) {
            null
        }

    override fun toString(): String = "SystemOneModelFactory(endpoint=$endpoint, defaultModel=$defaultModel)"

    private companion object {
        val logger = LoggerFactory.getLogger(SystemOneModelFactory::class.java)
    }
}
