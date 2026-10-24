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
import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.CredentialEndpoint
import com.embabel.common.ai.model.CredentialEndpointResolver
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelPlatform
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
 * [ModelPlatform] per wire protocol, each turning an endpoint of that protocol into clients for
 * the user's own key. The OpenAI-compatible platform builds chat and embedding models; the
 * Anthropic platform builds chat models only, because Anthropic has no embedding API.
 *
 * Where a key is sent is decided before that, and an application can decide it: each platform asks
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
 * `NoSuitableModelException` until the application registers a platform of its own - and that
 * platform is a call to the provider module's own factory, already on the classpath. Every
 * application that has needed one has written the same `when` over provider names, each copy a
 * place to get a provider name's casing wrong or fall behind a factory signature change, and each
 * failing at runtime rather than at compile time.
 *
 * These delegate to the provider modules, not to their autoconfigurations. That is the distinction
 * that makes shipping them possible at all: a pure BYOK deployment deliberately has no provider
 * autoconfiguration - `embabel-agent-starter-byok` bans it - but it does have the factories.
 *
 * No services are cached here. [com.embabel.common.ai.model.ConfigurableModelProvider] already
 * caches what a platform returns per (provider, key, model) behind a bounded LRU, so a cache here
 * would be a second, unbounded one holding a service per key the deployment has ever seen. The one
 * cache, in [OpenAiTimeouts], holds bound timeouts per provider, which is bounded by the number of
 * providers.
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

    /**
     * Created on first use, by an OpenAI bean only, so an application without `embabel-agent-openai`
     * never constructs it.
     */
    private val openAiTimeouts by lazy { OpenAiTimeouts(environment) }

    /**
     * Builds chat models for anything routed to Anthropic's protocol, whoever routed it there.
     *
     * Returns null for every other protocol, so the platform for that protocol gets its turn - the
     * same contract a hand-written [ModelPlatform] follows for a provider it does not handle.
     *
     * Chat only: Anthropic has no embedding API, so an Anthropic key asked for an embedding model
     * gets none here rather than somebody else's model.
     */
    @Bean(ANTHROPIC_MODEL_PLATFORM)
    @ConditionalOnClass(AnthropicModelFactory::class)
    @ConditionalOnMissingBean(name = [ANTHROPIC_MODEL_PLATFORM])
    fun anthropicModelPlatform(
        resolvers: ObjectProvider<CredentialEndpointResolver>,
    ): ModelPlatform {
        logger.info(
            "Per-user keys can build a chat service over Anthropic's protocol, for provider '{}' or any a CredentialEndpointResolver routes there",
            AnthropicModels.PROVIDER,
        )
        return ModelPlatform.builder()
            .builds(LlmService.KIND) { credential, model ->
                // The application's resolvers decide first; Anthropic's own endpoint is used only for its own key.
                val endpoint = resolvedByApplication(resolvers, credential, model) ?: anthropicEndpointFor(credential)
                // Null when the endpoint uses another protocol, so that protocol's platform answers.
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
            .build()
    }

    /**
     * Builds chat and embedding models for anything routed to the OpenAI wire protocol.
     *
     * Both kinds find the endpoint the same way, through [openAiCompatibleEndpoint], so a
     * [CredentialEndpointResolver] that routes a provider here routes both its chat and its
     * embedding models.
     *
     * Chat models are built without validating. The probe that
     * [OpenAiCompatibleModelFactory.buildValidated] makes belongs where a user first supplies a
     * key, not here: this runs on every cache miss, and it would validate against the spec's own
     * validation model rather than the one the role asked for.
     *
     * Embedding models are validated. A wrong chat model fails only the call that used it. An
     * embedding service reports a dimension, and that dimension becomes the size of a vector
     * index. A service built with a wrong dimension creates an index that later writes do not fit,
     * and nothing reports the error. [OpenAiCompatibleModelFactory.buildValidatedEmbeddingService]
     * reads the dimension from the provider's response. The cost is one probe per
     * (provider, key, model), since the platform caches what this returns.
     */
    @Bean(OPENAI_COMPATIBLE_MODEL_PLATFORM)
    @ConditionalOnClass(OpenAiCompatibleModelFactory::class)
    @ConditionalOnMissingBean(name = [OPENAI_COMPATIBLE_MODEL_PLATFORM])
    fun openAiCompatibleModelPlatform(
        resolvers: ObjectProvider<CredentialEndpointResolver>,
    ): ModelPlatform {
        logger.info(
            "Per-user keys can build chat and embedding services over the OpenAI protocol, for any provider {} knows or a CredentialEndpointResolver routes there",
            OpenAiCompatibleModelFactory::class.java.simpleName,
        )
        return ModelPlatform.builder()
            .builds(LlmService.KIND) { credential, model ->
                openAiCompatibleEndpoint(resolvers, credential, model)?.let {
                    openAiCompatibleFactory(it, credential, openAiTimeouts.forProvider(it.provider))
                        .openAiCompatibleLlm(
                            model = model,
                            pricingModel = it.pricingModel,
                            provider = it.provider,
                            knowledgeCutoffDate = it.knowledgeCutoffDate,
                        )
                }
            }
            .builds(EmbeddingService.KIND) { credential, model ->
                openAiCompatibleEndpoint(resolvers, credential, model)?.let {
                    openAiCompatibleFactory(it, credential, openAiTimeouts.forProvider(it.provider))
                        .buildValidatedEmbeddingService(
                            model = model,
                            provider = it.provider,
                            pricingModel = it.pricingModel,
                        )
                }
            }
            .build()
    }

    /**
     * The OpenAI-compatible endpoint for [credential], or null if it is routed to another protocol
     * or is a provider nothing knows.
     *
     * The application's resolvers decide first; the endpoints `embabel-agent-openai` knows are used
     * only when none of them answers.
     */
    private fun openAiCompatibleEndpoint(
        resolvers: ObjectProvider<CredentialEndpointResolver>,
        credential: ProviderCredential,
        model: String,
    ): CredentialEndpoint.OpenAiCompatible? =
        (resolvedByApplication(resolvers, credential, model) ?: openAiCompatibleEndpointFor(credential))
            // Null when the endpoint uses another protocol, so that protocol's platform answers.
            as? CredentialEndpoint.OpenAiCompatible

    /**
     * What the application says, in [org.springframework.core.Ordered] order, or null if none of
     * its resolvers claims this provider.
     *
     * Read on each call rather than captured, because the ordered stream is only complete once
     * every bean is. Reached on a cache miss rather than per call, since the platform caches the
     * service - but reached from each model platform that gets a turn, so a resolver can be asked
     * twice for one miss. Keep implementations pure and cheap, as their contract already asks.
     */
    private fun resolvedByApplication(
        resolvers: ObjectProvider<CredentialEndpointResolver>,
        credential: ProviderCredential,
        model: String,
    ): CredentialEndpoint? =
        resolvers.orderedStream().toList().firstNotNullOfOrNull { it.resolve(credential, model) }

    internal companion object {

        /** Bean name of the shipped Anthropic platform. A bean of this name replaces it. */
        const val ANTHROPIC_MODEL_PLATFORM = "anthropicModelPlatform"

        /** Bean name of the shipped OpenAI-compatible platform. A bean of this name replaces it, for chat and embedding. */
        const val OPENAI_COMPATIBLE_MODEL_PLATFORM = "openAiCompatibleModelPlatform"

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

/**
 * A client factory for [endpoint], authenticated with the user's key and the provider's configured
 * timeouts.
 *
 * A top-level function rather than a member of [CredentialEndpointConfig], for the same reason as
 * [OpenAiTimeouts]: its return type is from `embabel-agent-openai`, an optional dependency.
 */
private fun openAiCompatibleFactory(
    endpoint: CredentialEndpoint.OpenAiCompatible,
    credential: ProviderCredential,
    timeouts: OpenAiClientTimeouts,
): OpenAiCompatibleModelFactory =
    OpenAiCompatibleModelFactory(
        baseUrl = endpoint.baseUrl,
        apiKey = credential.apiKey,
        timeouts = timeouts,
    )

/**
 * Timeouts bound per provider, for the OpenAI beans only.
 *
 * Not part of [CredentialEndpointConfig], because these signatures name `embabel-agent-openai`
 * types, which is an optional dependency. Spring reads every method signature of a configuration
 * class when it creates a bean from it, so an OpenAI type there stops an application without that
 * module from starting, whatever `@ConditionalOnClass` says.
 */
private class OpenAiTimeouts(private val environment: Environment) {

    private val timeoutsByPrefix = ConcurrentHashMap<String, OpenAiClientTimeouts>()

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
    fun forProvider(provider: String): OpenAiClientTimeouts =
        timeoutsByPrefix.computeIfAbsent(CredentialEndpointConfig.timeoutPrefixFor(provider), ::bindTimeouts)

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
}
