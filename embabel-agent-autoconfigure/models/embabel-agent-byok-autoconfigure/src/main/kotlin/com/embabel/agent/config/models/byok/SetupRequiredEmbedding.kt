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
package com.embabel.agent.config.models.byok

import com.embabel.agent.spi.PlaceholderEmbeddingService
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel

/**
 * Thrown when work reaches the [SetupRequiredEmbedding] placeholder, meaning no real embedding
 * service was available: the deployment has no server-side key and none was supplied at runtime.
 *
 * Catch this to render your own "add an API key" experience. The placeholder fails loudly rather
 * than returning an empty vector or a plausible dimension, so a missing key surfaces as an
 * actionable error rather than as search results that are quietly wrong.
 */
class NoEmbeddingServiceConfiguredException(message: String) : RuntimeException(message)

/**
 * The placeholder embedding service for a pure BYOK deployment — one that starts with no provider
 * key at all and obtains a key per user, per tenant, or per request at runtime.
 *
 * Such a deployment has no [EmbeddingService] beans at startup, so anything that resolves the
 * default embedding service while its own beans are being created fails the context refresh —
 * exactly the problem [SetupRequiredLlm] solved for the chat side. Registering this placeholder and
 * pointing `default-embedding-model` at it gives the platform something to resolve, deferring the
 * "no key" failure from startup to the first call that actually needs an embedding.
 *
 * Opt in by putting `embabel-agent-starter-byok` on the classpath and configuring:
 * ```yaml
 * embabel:
 *   models:
 *     default-llm: setup-required
 *     default-embedding-model: setup-required-embedding
 * ```
 */
object SetupRequiredEmbedding {

    /**
     * The well-known name of the placeholder, as both the bean name and the [EmbeddingService]
     * name. Use it as the value of `embabel.models.default-embedding-model`.
     *
     * Distinct from [SetupRequiredLlm.NAME] because the two are selected by separate properties
     * and a shared name would make a mis-set property resolve to the wrong kind of model.
     */
    const val NAME: String = "setup-required-embedding"

    /** Provider reported by the placeholder. Not a real provider — no key reaches any endpoint. */
    const val PROVIDER: String = "none"

    /**
     * Message carried by [NoEmbeddingServiceConfiguredException]. Deliberately provider-neutral:
     * an application knows which providers it accepts and how a user supplies a key, so it should
     * catch the exception and say so in its own words rather than surface this verbatim.
     */
    val MESSAGE: String = """
        No embedding service is configured. This deployment holds no provider API key,
        so a key must be supplied at runtime before anything can be embedded or retrieved.
        See the Bring Your Own Key section of the Embabel reference documentation.
    """.trimIndent()

    /**
     * The message for a deployment that reached the placeholder WITH real embedding services
     * registered, which means a key is present and no model was chosen.
     *
     * [MESSAGE] would be a false statement here, and an expensive one: it sends somebody whose key
     * is working to go and debug the key, and the subsystem they then look at is the one that is
     * already right. An appliance cost a session that way - four chat models registered from a live
     * key, every embedding call reporting that the deployment held none.
     *
     * Naming the registered services is the load-bearing part: they are the values
     * `embabel.models.default-embedding-model` will accept, so the message carries its own fix.
     *
     * Assembled from whole paragraphs rather than by interpolating one raw string into another,
     * for the reason [SetupRequiredLlm.messageFor] gives at length: `trimIndent` computes the
     * common indent across the finished string, so an already-trimmed fragment drags it to zero
     * and leaves every surrounding line with its source indentation baked in.
     */
    fun messageForUnchosenDefault(availableServices: List<String>): String {
        val opening = """
            No embedding service is configured, but this deployment HOLDS a provider key and has
            registered embedding services - none was chosen, so the '$NAME' placeholder stands in.
            This is a choice not yet made, not a missing key.
        """.trimIndent()
        val fix = """
            Set embabel.models.default-embedding-model to one of: ${availableServices.joinToString()}
            - or to a role, which is resolved per call and so survives a key arriving later.
        """.trimIndent()
        return listOf(opening, fix).joinToString(separator = "\n")
    }

    /**
     * Builds the placeholder service. Registered as a bean by [SetupRequiredEmbeddingConfig]; call
     * this directly only when constructing a model provider outside a Spring context.
     *
     * @param availableRealServices names of the registered embedding services that are not
     * placeholders, read when a call fails rather than now - at construction there are none, since
     * provider autoconfigurations register their models after this bean is built. An empty list
     * means the deployment really does hold no key, which is the default and the pure-BYOK case.
     */
    @JvmOverloads
    fun embeddingService(availableRealServices: () -> List<String> = { emptyList() }): EmbeddingService =
        SetupRequiredEmbeddingService(availableRealServices)
}

/**
 * The placeholder as an [EmbeddingService], carrying the [PlaceholderEmbeddingService] marker the
 * platform looks for.
 *
 * Every member fails, [dimensions] included — see [PlaceholderEmbeddingService] for why a
 * placeholder that answered with a number would be worse than one that fails.
 */
internal class SetupRequiredEmbeddingService(
    private val availableRealServices: () -> List<String> = { emptyList() },
) : EmbeddingService, PlaceholderEmbeddingService {

    override val name: String = SetupRequiredEmbedding.NAME

    override val provider: String = SetupRequiredEmbedding.PROVIDER

    override val pricingModel: PricingModel? = null

    /**
     * Why this deployment has no embedding service, decided when the call fails rather than when
     * this was built - at construction the answer is always "no models registered", because
     * provider autoconfigurations register theirs later.
     */
    private fun failure(): NoEmbeddingServiceConfiguredException {
        val available = availableRealServices()
        return NoEmbeddingServiceConfiguredException(
            if (available.isEmpty()) SetupRequiredEmbedding.MESSAGE
            else SetupRequiredEmbedding.messageForUnchosenDefault(available),
        )
    }

    override fun embed(text: String): FloatArray = throw failure()

    override fun embed(texts: List<String>): List<FloatArray> = throw failure()

    /**
     * Fails rather than answering.
     *
     * A dimension is a schema commitment: whatever this returned would become the shape of a
     * vector index, and a real model configured later would disagree with everything written into
     * it. A consumer that provisions an index should test for [PlaceholderEmbeddingService] and
     * skip; this exists so that one which forgets fails loudly instead of building the wrong index.
     */
    override val dimensions: Int
        get() = throw failure()

    /**
     * The question consumers actually ask. It rides through `by` delegation, so a wrapper around
     * this one still answers true — which a `is PlaceholderEmbeddingService` test would not.
     */
    override val awaitingProviderKey: Boolean = true

    override fun toString(): String = "SetupRequiredEmbeddingService(name=$name)"
}
