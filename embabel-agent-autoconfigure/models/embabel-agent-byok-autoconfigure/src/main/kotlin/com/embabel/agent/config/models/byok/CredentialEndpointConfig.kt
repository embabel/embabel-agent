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

import com.embabel.agent.anthropic.AnthropicModelFactory
import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.openai.OpenAiClientTimeouts
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.common.ai.model.CredentialEmbeddingServiceFactory
import com.embabel.common.ai.model.CredentialEndpoint
import com.embabel.common.ai.model.CredentialEndpointResolver
import com.embabel.common.ai.model.CredentialLlmServiceFactory
import com.embabel.common.ai.model.ProviderCredential
import com.embabel.common.util.loggerFor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private const val MODELS_PREFIX = "embabel.agent.platform.models"

/**
 * Makes per-user keys work with nothing on the classpath but `embabel-agent-starter-byok`: one
 * [CredentialLlmServiceFactory] per wire protocol, each turning an endpoint of that protocol into
 * a client for the user's own key.
 *
 * Where a key is sent is decided before that, and an application can decide it: each factory asks
 * the registered [CredentialEndpointResolver] beans first and falls back to what this module knows
 * about the provider. So adding a provider - or overriding a shipped one's base URL - is a resolver
 * returning a value, with nothing from `com.embabel.agent.spi` in it, because a gateway speaking
 * the OpenAI protocol needs no client this module does not already have.
 *
 * That is one bean per provider *module*, not per provider: `embabel-agent-openai` speaks to five
 * providers over one wire protocol, so the coverage is Anthropic, OpenAI, DeepSeek, Mistral, Gemini
 * and Atlas Cloud - the whole BYOK surface. A deployment using any of them writes nothing at all.
 *
 * Without these, a [com.embabel.common.ai.model.RoleResolution.Credential] fails with
 * `NoSuitableModelException` until the application registers a factory of its own - and that
 * factory is a call to the provider module's own factory, already on the classpath. Every
 * application that has needed one has written the same `when` over provider names, each copy a
 * place to get a provider name's casing wrong or fall behind a factory signature change, and each
 * failing at runtime rather than at compile time.
 *
 * These delegate to the provider modules, not to their autoconfigurations. That is the distinction
 * that makes shipping them possible at all: a pure BYOK deployment deliberately has no provider
 * autoconfiguration - `embabel-agent-starter-byok` bans it - but it does have the factories.
 *
 * No services are cached here. [com.embabel.common.ai.model.ConfigurableModelProvider] already
 * caches what a factory returns per (provider, key, model) behind a bounded LRU, so a cache here
 * would be a second, unbounded one holding a service per key the deployment has ever seen. The one
 * cache here holds bound timeouts per provider, which is bounded by the number of providers.
 */
@Configuration(proxyBeanMethods = false)
class CredentialEndpointConfig @Autowired constructor(
    private val environment: Environment,
) {

    /**
     * For a caller building this configuration by hand. Timeouts then come from system properties
     * and environment variables only.
     */
    constructor() : this(StandardEnvironment())

    private val logger = loggerFor<CredentialEndpointConfig>()

    private val timeoutsByPrefix = ConcurrentHashMap<String, OpenAiClientTimeouts>()

    /**
     * Builds anything routed to Anthropic's protocol, whoever routed it there.
     *
     * Declines every other protocol rather than answering for it, so the factory for that protocol
     * gets its turn - the same contract a hand-written [CredentialLlmServiceFactory] follows for a
     * provider it does not handle.
     */
    @Bean("anthropicCredentialLlmServiceFactory")
    @ConditionalOnClass(AnthropicModelFactory::class)
    @ConditionalOnMissingBean(name = ["anthropicCredentialLlmServiceFactory"])
    fun anthropicCredentialLlmServiceFactory(
        resolvers: ObjectProvider<CredentialEndpointResolver>,
    ): CredentialLlmServiceFactory {
        logger.info(
            "Per-user keys can build a service over Anthropic's protocol, for provider '{}' or any a CredentialEndpointResolver routes there",
            AnthropicModels.PROVIDER,
        )
        return CredentialLlmServiceFactory { credential, model ->
            val endpoint = resolvedByApplication(resolvers, credential, model) ?: anthropicEndpointFor(credential)
            (endpoint as? CredentialEndpoint.Anthropic)?.let {
                AnthropicModelFactory(apiKey = credential.apiKey, baseUrl = it.baseUrl)
                    .build(
                        model = model,
                        provider = it.provider,
                        pricingModel = it.pricingModel,
                        knowledgeCutoffDate = it.knowledgeCutoffDate,
                    )
            }
        }
    }

    /**
     * The same for the OpenAI wire protocol.
     *
     * Builds without validating. The probe that [OpenAiCompatibleModelFactory.buildValidated] makes
     * belongs where a user first supplies a key, not here: this runs on every cache miss, and it
     * would validate against the spec's own validation model rather than the one the role asked for.
     */
    @Bean("openAiCompatibleCredentialLlmServiceFactory")
    @ConditionalOnClass(OpenAiCompatibleModelFactory::class)
    @ConditionalOnMissingBean(name = ["openAiCompatibleCredentialLlmServiceFactory"])
    fun openAiCompatibleCredentialLlmServiceFactory(
        resolvers: ObjectProvider<CredentialEndpointResolver>,
    ): CredentialLlmServiceFactory {
        logger.info(
            "Per-user keys can build a service over the OpenAI protocol, for any provider {} knows or a CredentialEndpointResolver routes there",
            OpenAiCompatibleModelFactory::class.java.simpleName,
        )
        return CredentialLlmServiceFactory { credential, model ->
            val endpoint = resolvedByApplication(resolvers, credential, model) ?: openAiCompatibleEndpointFor(credential)
            (endpoint as? CredentialEndpoint.OpenAiCompatible)?.let {
                OpenAiCompatibleModelFactory(
                    baseUrl = it.baseUrl,
                    apiKey = credential.apiKey,
                    timeouts = timeoutsFor(it.provider),
                )
                    .openAiCompatibleLlm(
                        model = model,
                        pricingModel = it.pricingModel,
                        provider = it.provider,
                        knowledgeCutoffDate = it.knowledgeCutoffDate,
                    )
            }
        }
    }

    /**
     * Embedding services from a per-user key, over the OpenAI protocol.
     *
     * One factory rather than one per protocol, because embedding has one protocol worth speaking:
     * Anthropic has no embedding API at all, so there is deliberately no Anthropic counterpart -
     * an Anthropic-keyed deployment declines rather than being handed somebody else's model.
     *
     * UNLIKE THE LLM FACTORIES ABOVE, THIS VALIDATES. They build without probing, because a chat
     * model that turns out to be wrong fails the call that used it and nothing else. An embedding
     * service states a WIDTH, and that width becomes the shape of a vector index - so one built on
     * an unverified assumption is an index that accepts writes no later model agrees with, and the
     * damage is silent and durable. [OpenAiCompatibleModelFactory.buildValidatedEmbeddingService]
     * observes the dimension the provider actually returns. The cost is one probe per
     * (provider, key, model), since the platform caches what this returns.
     */
    @Bean("openAiCompatibleCredentialEmbeddingServiceFactory")
    @ConditionalOnClass(OpenAiCompatibleModelFactory::class)
    @ConditionalOnMissingBean(name = ["openAiCompatibleCredentialEmbeddingServiceFactory"])
    fun openAiCompatibleCredentialEmbeddingServiceFactory(
        resolvers: ObjectProvider<CredentialEndpointResolver>,
    ): CredentialEmbeddingServiceFactory {
        logger.info(
            "Per-user keys can build an embedding service over the OpenAI protocol, for any provider {} knows or a CredentialEndpointResolver routes there",
            OpenAiCompatibleModelFactory::class.java.simpleName,
        )
        return CredentialEmbeddingServiceFactory { credential, model ->
            val endpoint = resolvedByApplication(resolvers, credential, model) ?: openAiCompatibleEndpointFor(credential)
            (endpoint as? CredentialEndpoint.OpenAiCompatible)?.let {
                OpenAiCompatibleModelFactory(
                    baseUrl = it.baseUrl,
                    apiKey = credential.apiKey,
                    timeouts = timeoutsFor(it.provider),
                )
                    .buildValidatedEmbeddingService(
                        model = model,
                        provider = it.provider,
                        pricingModel = it.pricingModel,
                    )
            }
        }
    }

    /**
     * What the application says, in [org.springframework.core.Ordered] order, or null if none of
     * its resolvers claims this provider.
     *
     * Read on each call rather than captured, because the ordered stream is only complete once
     * every bean is. Reached on a cache miss rather than per call, since the platform caches the
     * service - but reached from each factory that gets a turn, so a resolver can be asked twice
     * for one miss. Keep implementations pure and cheap, as their contract already asks.
     */
    private fun resolvedByApplication(
        resolvers: ObjectProvider<CredentialEndpointResolver>,
        credential: ProviderCredential,
        model: String,
    ): CredentialEndpoint? =
        resolvers.orderedStream().toList().firstNotNullOfOrNull { it.resolve(credential, model) }

    /**
     * The timeouts configured for [provider], under the same prefix its platform configuration
     * uses - `embabel.agent.platform.models.openai.read-timeout` applies to a user's OpenAI key as
     * it does to the deployment's. How long a model takes depends on the endpoint, not on whose key
     * pays for the call.
     *
     * Bound from the environment rather than read from the provider's properties bean, because a
     * pure BYOK deployment has no provider autoconfiguration and so no such bean. A provider an
     * application's resolver adds is configured the same way, under its own normalised name.
     *
     * Bound on the first call for a provider and cached per prefix after that, because the set of
     * providers is open - a resolver can route to one this configuration has never heard of - so
     * there is no list to bind eagerly at startup. The cost is that a malformed value, say
     * `read-timeout: 5 minutes`, is not reported at startup: it surfaces on the first call for that
     * provider, as an [InvalidProviderTimeoutException] naming the property, and on every call after
     * it until fixed. A failed bind is not cached, and never falls back to a default.
     */
    private fun timeoutsFor(provider: String): OpenAiClientTimeouts =
        timeoutsByPrefix.computeIfAbsent(timeoutPrefixFor(provider), ::bindTimeouts)

    private fun bindTimeouts(prefix: String): OpenAiClientTimeouts {
        val binder = Binder.get(environment)
        return OpenAiClientTimeouts(
            connect = bindDuration(binder, "$prefix.connect-timeout") ?: OpenAiClientTimeouts.DEFAULT_CONNECT,
            read = bindDuration(binder, "$prefix.read-timeout"),
        )
    }

    private fun bindDuration(binder: Binder, property: String): Duration? =
        try {
            binder.bind(property, Duration::class.java).orElse(null)
        } catch (e: BindException) {
            throw InvalidProviderTimeoutException(property, e)
        }

    internal companion object {

        /**
         * `Mistral AI` becomes `embabel.agent.platform.models.mistralai`, matching the provider
         * modules' own prefixes. Gemini is the exception: over this protocol it is configured as
         * `gemini`, while `googlegenai` belongs to the native Google GenAI module.
         */
        internal fun timeoutPrefixFor(provider: String): String {
            val name = provider.lowercase().filter(Char::isLetterOrDigit)
            return "$MODELS_PREFIX.${if (name == GoogleGenAiModels.PROVIDER.lowercase()) "gemini" else name}"
        }
    }

    /**
     * Anthropic's own endpoint, or null if this is not Anthropic's key.
     *
     * Not a [CredentialEndpointResolver] bean, deliberately. A bean would sort by
     * [org.springframework.core.Ordered], where an application resolver carrying no `@Order` is
     * `LOWEST_PRECEDENCE` - a tie with anything shipped, broken by bean registration order, which
     * is not something an application should have to reason about to override a base URL. Consulted
     * after the beans instead, so the application always wins. [com.embabel.common.ai.model.RoleResolver]
     * keeps its configuration-driven resolver out of the bean stream for the same reason.
     */
    private fun anthropicEndpointFor(credential: ProviderCredential): CredentialEndpoint? =
        if (!credential.provider.equals(AnthropicModels.PROVIDER, ignoreCase = true)) null
        else CredentialEndpoint.Anthropic(provider = AnthropicModels.PROVIDER)

    /**
     * Every OpenAI-compatible provider, on the same terms.
     *
     * One lookup rather than one per provider, because `embabel-agent-openai` carries OpenAI,
     * DeepSeek, Mistral, Gemini and Atlas Cloud behind a single wire protocol, and the only thing
     * that differs is the base URL - which [OpenAiCompatibleModelFactory.endpointFor] already
     * knows. Reading it there rather than restating it keeps this from drifting when an endpoint
     * moves.
     */
    private fun openAiCompatibleEndpointFor(credential: ProviderCredential): CredentialEndpoint? =
        OpenAiCompatibleModelFactory.endpointFor(credential.provider)?.let {
            CredentialEndpoint.OpenAiCompatible(provider = it.provider, baseUrl = it.baseUrl)
        }
}
