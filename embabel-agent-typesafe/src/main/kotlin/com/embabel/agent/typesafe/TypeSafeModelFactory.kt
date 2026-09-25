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
package com.embabel.agent.typesafe

import com.embabel.common.ai.model.DecisionService
import com.embabel.common.ai.model.ObservedDecisionService
import com.embabel.common.byok.ByokFactory
import com.embabel.common.byok.InvalidApiKeyException
import com.embabel.common.byok.requireUsableApiKey
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.util.concurrent.CancellationException
import java.util.function.Supplier

/**
 * Builds Embabel decision services backed by TypeSafe models.
 *
 * Provider settings and credentials belong to the factory; [build] selects a model. Each returned
 * service also implements classification because [DecisionService] extends the narrower
 * classification contract. The native SDK client remains an implementation detail, so callers
 * retain provider-neutral requests, results, metadata and observability.
 *
 * [buildValidated] follows the same BYOK contract as the OpenAI and Anthropic factories. Ordinary
 * [build] calls remain local and do not resolve credentials or contact the provider.
 */
@ApiStatus.Experimental
open class TypeSafeModelFactory private constructor(
    private val clients: TypeSafeClientFactory,
    private val keySupplier: Supplier<String>,
    private val defaultModel: String,
    protected val observationRegistry: ObservationRegistry,
) : ByokFactory<DecisionService> {

    /** Uses provider defaults, the fallback transport and a no-op observation registry. */
    @JvmOverloads
    constructor(
        keySupplier: Supplier<String>,
        defaultModel: String = DEFAULT_MODEL,
    ) : this(
        clients = TypeSafeClientFactory(TypeSafeClientOptions.defaults(), keySupplier),
        keySupplier = keySupplier,
        defaultModel = defaultModel,
        observationRegistry = ObservationRegistry.NOOP,
    )

    /**
     * Configures the guarded provider boundary shared by services built by this factory.
     *
     * @param options non-secret provider settings
     * @param keySupplier credential source evaluated for each request
     * @param restClientBuilder application builder to clone, or null for the fallback transport
     * @param observationRegistry registry for framework, provider and fallback HTTP observations
     * @param defaultModel model returned by [build] and [buildValidated]
     */
    @JvmOverloads
    constructor(
        options: TypeSafeClientOptions,
        keySupplier: Supplier<String>,
        restClientBuilder: RestClient.Builder? = null,
        observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
        defaultModel: String = DEFAULT_MODEL,
    ) : this(
        clients = TypeSafeClientFactory(options, keySupplier, restClientBuilder, observationRegistry),
        keySupplier = keySupplier,
        defaultModel = defaultModel,
        observationRegistry = observationRegistry,
    )

    /** Builds a decision service using this factory's default model. */
    fun build(): DecisionService = build(defaultModel)

    /**
     * Builds a decision service for one model identifier or alias.
     *
     * Services share the factory's guarded transport while keeping independent model metadata. The
     * returned object can be injected as either [DecisionService] or its classification supertype.
     */
    fun build(model: String): DecisionService =
        ObservedDecisionService(TypeSafeDecisionService(clients.build(model)), observationRegistry)

    /**
     * Validates the current credential against the provider and returns the default model service.
     * Provider-specific failures are collapsed to [InvalidApiKeyException] without retaining raw
     * response text or credentials.
     */
    override fun buildValidated(): DecisionService {
        try {
            requireUsableApiKey(keySupplier.get())
            clients.validate(defaultModel)
            logger.debug("TypeSafe credential validation succeeded")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            logger.warn("TypeSafe credential validation failed")
            throw InvalidApiKeyException("TypeSafe credential could not be validated")
        }
        return build()
    }

    companion object {
        /** Provider name reported by every service and result produced by this factory. */
        const val PROVIDER = "TypeSafe"

        /** TypeSafe's default Jev model alias. */
        const val DEFAULT_MODEL = "jev-latest"

        private val logger = LoggerFactory.getLogger(TypeSafeModelFactory::class.java)
    }
}
