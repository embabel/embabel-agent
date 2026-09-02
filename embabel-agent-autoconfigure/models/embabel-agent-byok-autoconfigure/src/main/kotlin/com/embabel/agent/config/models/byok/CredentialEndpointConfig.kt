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
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.common.ai.model.CredentialEndpoint
import com.embabel.common.ai.model.CredentialEndpointResolver
import com.embabel.common.ai.model.CredentialLlmServiceFactory
import com.embabel.common.ai.model.ProviderCredential
import com.embabel.common.util.loggerFor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
 * That is one bean per provider *module*, not per provider: `embabel-agent-openai` speaks to six
 * providers over one wire protocol, so the coverage is Anthropic, OpenAI, DeepSeek, Mistral, Gemini,
 * Atlas Cloud and OrcaRouter - the whole BYOK surface. A deployment using any of them writes nothing at all.
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
 * Nothing here caches. [com.embabel.common.ai.model.ConfigurableModelProvider] already caches what
 * a factory returns per (provider, key, model) behind a bounded LRU, so a cache here would be a
 * second, unbounded one holding a service per key the deployment has ever seen.
 */
@Configuration(proxyBeanMethods = false)
class CredentialEndpointConfig {

    private val logger = loggerFor<CredentialEndpointConfig>()

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
                OpenAiCompatibleModelFactory(baseUrl = it.baseUrl, apiKey = credential.apiKey)
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
     * DeepSeek, Mistral, Gemini, Atlas Cloud and OrcaRouter behind a single wire protocol, and the
     * only thing that differs is the base URL - which [OpenAiCompatibleModelFactory.endpointFor]
     * already knows. Reading it there rather than restating it keeps this from drifting when an
     * endpoint moves.
     */
    private fun openAiCompatibleEndpointFor(credential: ProviderCredential): CredentialEndpoint? =
        OpenAiCompatibleModelFactory.endpointFor(credential.provider)?.let {
            CredentialEndpoint.OpenAiCompatible(provider = it.provider, baseUrl = it.baseUrl)
        }
}
