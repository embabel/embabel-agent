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

import java.time.LocalDate

/**
 * Where a user's key should be sent, and what the service built from it should say about itself.
 *
 * A value rather than a service: the application states which endpoint speaks for a provider, and
 * the platform builds the client, so nothing here names a type from `com.embabel.agent.spi`. That
 * is the point - [CredentialLlmServiceFactory] is the other way round, and requires a package the
 * documentation asks application code not to depend on.
 *
 * The case is the wire protocol, because that is what decides which client can talk to the
 * endpoint. Everything that varies within a protocol is a field. A protocol this framework has no
 * client for is not a case here: register a [CredentialLlmServiceFactory] for that, accepting the
 * SPI dependency it carries.
 */
sealed interface CredentialEndpoint {

    /**
     * Provider name the built service reports, which is what cost accounting and metadata lookups
     * key on. Usually the same name the credential carries, but it need not be: a credential holds
     * whatever spelling the application stored, and this is the framework's own.
     */
    val provider: String

    /**
     * Base URL to talk to, or null for the protocol's default host.
     *
     * Non-null is the usual case for a gateway or a proxy - which, along with a provider this
     * framework does not ship, is the reason to write a resolver at all. Set it. Null on an
     * [OpenAiCompatible] endpoint means OpenAI's own servers, so a null that reached one by
     * accident - an unset property, say - would send a gateway's key to OpenAI under the gateway's
     * name. The one legitimate null is OpenAI itself, which is why the type still permits it.
     *
     * [OpenAiCompatible] states it and [Anthropic] defaults it: "Anthropic's protocol" names a
     * host, "OpenAI-compatible" names none.
     */
    val baseUrl: String?

    /**
     * Defaults to [PricingModel.ALL_YOU_CAN_EAT] - zero - because a BYOK call is billed to the
     * user's own key rather than to the deployment, so charging it to the deployment's cost
     * accounting would be wrong in the one direction that matters. Set it only if you are
     * reselling the call and do want it counted.
     */
    val pricingModel: PricingModel

    /**
     * Null unless you know it for the model the role named. The name comes from configuration and
     * may be one this framework version has never heard of, so a cutoff stated here would be a
     * guess, and it reaches the LLM as a prompt contribution.
     */
    val knowledgeCutoffDate: LocalDate?

    /**
     * The OpenAI wire protocol, which most providers now speak: OpenAI itself, DeepSeek, Mistral,
     * Gemini and Atlas Cloud all reach the platform this way, as does the average self-hosted
     * gateway.
     */
    data class OpenAiCompatible @JvmOverloads constructor(
        override val provider: String,
        override val baseUrl: String?,
        override val pricingModel: PricingModel = PricingModel.ALL_YOU_CAN_EAT,
        override val knowledgeCutoffDate: LocalDate? = null,
    ) : CredentialEndpoint

    /**
     * Anthropic's own protocol, for Anthropic and anything fronting it.
     */
    data class Anthropic @JvmOverloads constructor(
        override val provider: String,
        override val baseUrl: String? = null,
        override val pricingModel: PricingModel = PricingModel.ALL_YOU_CAN_EAT,
        override val knowledgeCutoffDate: LocalDate? = null,
    ) : CredentialEndpoint
}

/**
 * Says where a user's key should be sent, for the providers this application knows about.
 *
 * Application API, not SPI: you implement it and register it as a bean, and the platform calls it.
 * The extension point for BYOK against a provider the framework does not ship. Register as many as
 * you like: the first non-null answer wins, in [org.springframework.core.Ordered] order, so a
 * resolver answers for the providers it knows and returns null for the rest. What reads them is
 * the `embabel-agent-starter-byok` machinery that builds services from user keys, so a deployment
 * without that starter can register these and never be asked.
 *
 * What these beans say is the first word, and the endpoints `embabel-agent-starter-byok` knows for
 * Anthropic, OpenAI, DeepSeek, Mistral, Gemini and Atlas Cloud are the last: answering for one of
 * those overrides it, for a proxy or a custom base URL, with no `@Order` needed. The shipped
 * endpoints are not beans, so they cannot tie with yours - `@Order` decides only which of *your*
 * resolvers is asked first, and ties between them fall back to bean registration order.
 *
 * ```kotlin
 * @Bean
 * fun ourGatewayEndpoint() = CredentialEndpointResolver { credential, _ ->
 *     if (!credential.provider.equals("OurGateway", ignoreCase = true)) null
 *     else CredentialEndpoint.OpenAiCompatible(provider = "OurGateway", baseUrl = GATEWAY_URL)
 * }
 * ```
 *
 * Return null rather than an endpoint for a provider you do not handle: a resolver that answers for
 * everything would point someone else's key at your gateway.
 *
 * The platform caches the service it builds, per (provider, key, model), so a resolver is consulted
 * on a cache miss rather than on every call - though possibly more than once within one, as each
 * wire protocol's builder gets its turn. Implementations must be thread-safe, and should be pure
 * and cheap for the same reason.
 *
 * That cache is keyed on (provider, key, model) and not on what you return here, so a resolver that
 * answers differently for the same three - per tenant, say, read from some ambient context - will
 * have its first answer serve every later caller sharing them.
 */
fun interface CredentialEndpointResolver {

    /**
     * @param credential the user's key, and the provider name it was stored under
     * @param model the model the role named, which may be one this framework version does not know
     * @return where to send that key, or null to let the next resolver decide
     */
    fun resolve(credential: ProviderCredential, model: String): CredentialEndpoint?
}
