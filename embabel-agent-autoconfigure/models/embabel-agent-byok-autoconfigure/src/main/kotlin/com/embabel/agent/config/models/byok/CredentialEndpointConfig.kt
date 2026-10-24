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
import com.embabel.agent.anthropic.AnthropicPlatform
import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.openai.DeepSeekPlatform
import com.embabel.agent.openai.MistralAiPlatform
import com.embabel.agent.openai.OpenAiClientTimeouts
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.openai.OpenAiCompatiblePlatform
import com.embabel.agent.openai.OpenAiPlatform
import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.CredentialEndpoint
import com.embabel.common.ai.model.CredentialEndpointResolver
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelCapability
import com.embabel.common.ai.model.ModelKind
import com.embabel.common.ai.model.ModelPlatform
import com.embabel.common.ai.model.ProviderCredential
import com.embabel.common.util.loggerFor
import org.slf4j.Logger
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
 * Makes per-user keys work with nothing on the classpath but `embabel-agent-starter-byok`. It
 * registers one [ModelPlatform] bean per provider that has a named platform, plus one bean for
 * every other OpenAI-compatible endpoint:
 *
 * | Bean | Platform | Kinds |
 * |---|---|---|
 * | `openAiModelPlatform` | [OpenAiPlatform] | chat, embedding |
 * | `deepSeekModelPlatform` | [DeepSeekPlatform] | chat |
 * | `mistralAiModelPlatform` | [MistralAiPlatform] | chat, embedding |
 * | `anthropicModelPlatform` | [AnthropicPlatform] | chat |
 * | `openAiCompatibleModelPlatform` | built with [OpenAiCompatiblePlatform.modelAt] | chat, embedding |
 *
 * `openAiCompatibleModelPlatform` builds for two groups of keys: a key that an application's
 * [CredentialEndpointResolver] sends to an OpenAI-compatible endpoint, and a key for a provider
 * that `embabel-agent-openai` knows but that has no named platform yet (Gemini and Atlas Cloud).
 *
 * The application's resolvers are asked first. When a resolver returns an endpoint for a key, the
 * named platforms return null for it, and `openAiCompatibleModelPlatform` (for the OpenAI protocol)
 * or `anthropicModelPlatform` (for Anthropic's protocol) builds it at that endpoint. So a resolver
 * that answers for "OpenAI" sends OpenAI keys to its proxy, whatever order the beans are in.
 *
 * Each bean is skipped when the application registers a bean with the same name. Replacing one
 * leaves the others in place: replacing `deepSeekModelPlatform` changes what a DeepSeek key builds
 * and nothing else.
 *
 * A key for a provider whose platform does not list a kind gets no model of that kind. For
 * example, a DeepSeek key asked for an embedding model: `deepSeekModelPlatform` lists chat only,
 * and `openAiCompatibleModelPlatform` leaves DeepSeek to its named platform. The role then fails
 * with `NoSuitableModelException` and a warning naming the provider, before any HTTP request.
 *
 * These beans call the provider modules (`embabel-agent-openai`, `embabel-agent-anthropic`), not
 * their autoconfigurations. A pure BYOK deployment has no provider autoconfiguration, because
 * `embabel-agent-starter-byok` bans it, but it does have the provider modules.
 *
 * No models are cached here. [com.embabel.common.ai.model.ConfigurableModelProvider] already
 * caches what a platform returns per (provider, key, model) in a bounded LRU cache. The one cache,
 * in [OpenAiTimeouts], holds bound timeouts per provider, so its size is the number of providers.
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

    /** Chat models for an OpenAI key. Embedding models too. */
    @Bean(OPENAI_MODEL_PLATFORM)
    @ConditionalOnClass(OpenAiCompatibleModelFactory::class)
    @ConditionalOnMissingBean(name = [OPENAI_MODEL_PLATFORM])
    fun openAiModelPlatform(resolvers: ObjectProvider<CredentialEndpointResolver>): ModelPlatform =
        applicationResolversFirst(OpenAiPlatform(), resolvers, openAiTimeouts, logger)

    /** Chat models for a DeepSeek key. DeepSeek has no embeddings API. */
    @Bean(DEEP_SEEK_MODEL_PLATFORM)
    @ConditionalOnClass(OpenAiCompatibleModelFactory::class)
    @ConditionalOnMissingBean(name = [DEEP_SEEK_MODEL_PLATFORM])
    fun deepSeekModelPlatform(resolvers: ObjectProvider<CredentialEndpointResolver>): ModelPlatform =
        applicationResolversFirst(DeepSeekPlatform(), resolvers, openAiTimeouts, logger)

    /** Chat and embedding models for a Mistral AI key. */
    @Bean(MISTRAL_AI_MODEL_PLATFORM)
    @ConditionalOnClass(OpenAiCompatibleModelFactory::class)
    @ConditionalOnMissingBean(name = [MISTRAL_AI_MODEL_PLATFORM])
    fun mistralAiModelPlatform(resolvers: ObjectProvider<CredentialEndpointResolver>): ModelPlatform =
        applicationResolversFirst(MistralAiPlatform(), resolvers, openAiTimeouts, logger)

    /**
     * Chat models for an Anthropic key, and for any key a [CredentialEndpointResolver] sends to an
     * Anthropic-protocol endpoint. Anthropic has no embeddings API.
     */
    @Bean(ANTHROPIC_MODEL_PLATFORM)
    @ConditionalOnClass(AnthropicModelFactory::class)
    @ConditionalOnMissingBean(name = [ANTHROPIC_MODEL_PLATFORM])
    fun anthropicModelPlatform(resolvers: ObjectProvider<CredentialEndpointResolver>): ModelPlatform =
        anthropicWithApplicationResolvers(resolvers, logger)

    /**
     * Chat and embedding models for every other key whose endpoint speaks the OpenAI protocol:
     * a key a [CredentialEndpointResolver] sends to such an endpoint, or a key for a provider
     * `embabel-agent-openai` knows that has no named platform (Gemini, Atlas Cloud).
     *
     * Embedding models are built with one test call, which reads the vector width from the
     * server's answer; see [OpenAiCompatiblePlatform.modelAt].
     */
    @Bean(OPENAI_COMPATIBLE_MODEL_PLATFORM)
    @ConditionalOnClass(OpenAiCompatibleModelFactory::class)
    @ConditionalOnMissingBean(name = [OPENAI_COMPATIBLE_MODEL_PLATFORM])
    fun openAiCompatibleModelPlatform(resolvers: ObjectProvider<CredentialEndpointResolver>): ModelPlatform {
        // Providers with a named platform are left to it, even when that bean was replaced.
        val namedProviders = listOf(OpenAiPlatform(), DeepSeekPlatform(), MistralAiPlatform()).map { it.provider }
        logger.info(
            "Per-user keys can build chat and embedding models over the OpenAI protocol for endpoints a CredentialEndpointResolver returns, and for known providers other than {}",
            namedProviders,
        )
        return ModelPlatform.builder()
            .builds(LlmService.KIND) { credential, model ->
                openAiCompatibleModel(resolvers, namedProviders, credential, LlmService.KIND, model)
            }
            .builds(EmbeddingService.KIND) { credential, model ->
                openAiCompatibleModel(resolvers, namedProviders, credential, EmbeddingService.KIND, model)
            }
            .build()
    }

    /**
     * A model of [kind] for [credential] at the OpenAI-compatible endpoint chosen for it, or null
     * when there is none.
     *
     * The endpoint is the application resolvers' answer if there is one. Without one, it is the
     * endpoint `embabel-agent-openai` knows for the provider, unless the provider is one of
     * [namedProviders].
     */
    private fun <C : ModelCapability> openAiCompatibleModel(
        resolvers: ObjectProvider<CredentialEndpointResolver>,
        namedProviders: List<String>,
        credential: ProviderCredential,
        kind: ModelKind<C, *>,
        model: String,
    ): C? {
        val endpoint = resolvedByApplication(resolvers, credential, model)
            ?: knownEndpointWithoutNamedPlatform(credential, namedProviders)
        // A resolver may choose Anthropic's protocol; anthropicModelPlatform builds that one.
        return (endpoint as? CredentialEndpoint.OpenAiCompatible)?.let {
            OpenAiCompatiblePlatform.modelAt(it, credential.apiKey, kind, model, openAiTimeouts.forProvider(it.provider))
        }
    }

    /**
     * The endpoint `embabel-agent-openai` knows for the credential's provider, or null if it knows
     * none or the provider is one of [namedProviders]. For example, "Gemini" gives Gemini's
     * endpoint, and "DeepSeek" gives null because [DeepSeekPlatform] builds DeepSeek keys.
     *
     * Read from [OpenAiCompatibleModelFactory.endpointFor], so the base URLs are written down once.
     */
    private fun knownEndpointWithoutNamedPlatform(
        credential: ProviderCredential,
        namedProviders: List<String>,
    ): CredentialEndpoint? =
        OpenAiCompatibleModelFactory.endpointFor(credential.provider)
            ?.takeUnless { it.provider in namedProviders }
            ?.let { CredentialEndpoint.OpenAiCompatible(provider = it.provider, baseUrl = it.baseUrl) }

    internal companion object {

        /** Bean name of the shipped OpenAI platform. A bean of this name replaces it. */
        const val OPENAI_MODEL_PLATFORM = "openAiModelPlatform"

        /** Bean name of the shipped DeepSeek platform. A bean of this name replaces it. */
        const val DEEP_SEEK_MODEL_PLATFORM = "deepSeekModelPlatform"

        /** Bean name of the shipped Mistral AI platform. A bean of this name replaces it. */
        const val MISTRAL_AI_MODEL_PLATFORM = "mistralAiModelPlatform"

        /** Bean name of the shipped Anthropic platform. A bean of this name replaces it. */
        const val ANTHROPIC_MODEL_PLATFORM = "anthropicModelPlatform"

        /**
         * Bean name of the shipped platform for other OpenAI-compatible endpoints: those an
         * application's resolvers return, and Gemini and Atlas Cloud. A bean of this name replaces
         * it, for chat and embedding.
         */
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
}

/**
 * What the application says, in [org.springframework.core.Ordered] order, or null if none of
 * its resolvers claims this provider.
 *
 * Read on each call rather than captured, because the ordered stream is only complete once
 * every bean is. Reached on a cache miss rather than per call, since the platform caches the
 * service - but reached from each model platform that gets a turn: the named platform for the
 * key's provider, `anthropicModelPlatform` and `openAiCompatibleModelPlatform`. So a resolver
 * can be asked up to three times for one miss. Keep implementations pure and cheap, as their
 * contract already asks.
 */
private fun resolvedByApplication(
    resolvers: ObjectProvider<CredentialEndpointResolver>,
    credential: ProviderCredential,
    model: String,
): CredentialEndpoint? =
    resolvers.orderedStream().toList().firstNotNullOfOrNull { it.resolve(credential, model) }

private fun kindNames(platform: ModelPlatform): String = platform.kinds.joinToString(" and ") { it.name }

/**
 * [platform] as a bean that leaves the application's endpoints to other beans: it returns null
 * for a key that a [CredentialEndpointResolver] answers for, so the bean for that endpoint's
 * protocol builds it.
 *
 * For example, with a resolver that sends "OpenAI" keys to a proxy, `openAiModelPlatform`
 * returns null for an OpenAI key and `openAiCompatibleModelPlatform` builds it at the proxy.
 *
 * A top-level function rather than a member of [CredentialEndpointConfig], for the same reason as
 * [OpenAiTimeouts]: [platform]'s type is from `embabel-agent-openai`, an optional dependency.
 */
private fun applicationResolversFirst(
    platform: OpenAiCompatiblePlatform,
    resolvers: ObjectProvider<CredentialEndpointResolver>,
    timeouts: OpenAiTimeouts,
    logger: Logger,
): ModelPlatform {
    logger.info("Per-user keys can build {} models for {}", kindNames(platform), platform)
    return object : ModelPlatform {

        override val kinds: Set<ModelKind<*, *>> = platform.kinds

        override fun <C : ModelCapability> model(
            credential: ProviderCredential,
            kind: ModelKind<C, *>,
            model: String,
        ): C? = when {
            // Checked first, so neither the resolvers nor this provider's timeouts are read for another provider's key.
            !platform.serves(credential) -> null
            // A resolver decides where this key goes, and the bean for that protocol builds it.
            resolvedByApplication(resolvers, credential, model) != null -> {
                logger.debug(
                    "{} leaves model '{}' for provider '{}' to the endpoint a CredentialEndpointResolver returned",
                    platform,
                    model,
                    credential.provider,
                )
                null
            }
            // The timeouts for this provider are bound on its first call; see OpenAiTimeouts.forProvider.
            else -> platform.withTimeouts(timeouts.forProvider(platform.provider)).model(credential, kind, model)
        }

        override fun toString(): String = platform.toString()
    }
}

/**
 * [AnthropicPlatform], building at the Anthropic-protocol endpoint a [CredentialEndpointResolver]
 * returns, when one does.
 *
 * A top-level function rather than the body of [CredentialEndpointConfig.anthropicModelPlatform]:
 * the builder's lambda captures the [AnthropicPlatform], and a captured value becomes a parameter
 * of a method on the class that declares the lambda. `embabel-agent-anthropic` is an optional
 * dependency; see [OpenAiTimeouts].
 */
private fun anthropicWithApplicationResolvers(
    resolvers: ObjectProvider<CredentialEndpointResolver>,
    logger: Logger,
): ModelPlatform {
    val anthropic = AnthropicPlatform()
    logger.info("Per-user keys can build {} models for {}", kindNames(anthropic), anthropic)
    return ModelPlatform.builder()
        .builds(LlmService.KIND) { credential, model ->
            when (val endpoint = resolvedByApplication(resolvers, credential, model)) {
                // No resolver answered: AnthropicPlatform builds for Anthropic's own key only.
                null -> anthropic.model(credential, LlmService.KIND, model)
                // A resolver chose an Anthropic-protocol endpoint, so build there.
                is CredentialEndpoint.Anthropic -> AnthropicPlatform.modelAt(endpoint, credential.apiKey, model)
                // A resolver chose the OpenAI protocol, so openAiCompatibleModelPlatform builds it.
                else -> null
            }
        }
        .build()
}

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
