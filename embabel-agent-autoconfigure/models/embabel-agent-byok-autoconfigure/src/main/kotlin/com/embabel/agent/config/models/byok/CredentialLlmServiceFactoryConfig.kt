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
import com.embabel.common.ai.model.CredentialLlmServiceFactory
import com.embabel.common.ai.model.PricingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Ships a [CredentialLlmServiceFactory] for every provider this module can see, so that per-user
 * keys work with nothing on the classpath but `embabel-agent-starter-byok`.
 *
 * That is one bean per provider *module*, not per provider: `embabel-agent-openai` speaks to five
 * providers over one wire protocol, so the coverage is Anthropic, OpenAI, DeepSeek, Mistral, Gemini
 * and Atlas Cloud - the whole BYOK surface. A deployment using any of them writes no factory at all.
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
class CredentialLlmServiceFactoryConfig {

    /**
     * Anthropic, gated on the provider module being present.
     *
     * `@ConditionalOnMissingBean` by name rather than by type: the two factories here are both
     * [CredentialLlmServiceFactory]s and the platform consults all of them, so a by-type condition
     * would let whichever bean happened to be defined first suppress the other. By name, an
     * application overriding one - for a custom base URL, or a proxy - keeps the other.
     */
    @Bean("anthropicCredentialLlmServiceFactory")
    @ConditionalOnClass(AnthropicModelFactory::class)
    @ConditionalOnMissingBean(name = ["anthropicCredentialLlmServiceFactory"])
    fun anthropicCredentialLlmServiceFactory() = CredentialLlmServiceFactory { credential, model ->
        if (!credential.provider.equals(AnthropicModels.PROVIDER, ignoreCase = true)) null
        else AnthropicModelFactory(apiKey = credential.apiKey).build(model)
    }

    /**
     * Every OpenAI-compatible provider, on the same terms.
     *
     * One bean rather than one per provider, because the module gate is the same for all of them:
     * `embabel-agent-openai` carries OpenAI, DeepSeek, Mistral, Gemini and Atlas Cloud behind a
     * single wire protocol, and the only thing that differs is the base URL - which
     * [OpenAiCompatibleModelFactory.endpointFor] already knows. Reading it there rather than
     * restating it keeps this from drifting when an endpoint moves.
     *
     * `pricingModel` is [PricingModel.ALL_YOU_CAN_EAT] - zero - because the call is billed to the
     * user's own key, not to the deployment, so charging it to the deployment's cost accounting
     * would be wrong in the one direction that matters. `knowledgeCutoffDate` is null because the
     * model name comes from configuration and may be one this framework version has never heard of;
     * stating a cutoff for it would be a guess.
     *
     * Builds without validating. The probe that [OpenAiCompatibleModelFactory.buildValidated] makes
     * belongs where a user first supplies a key, not here: this runs on every cache miss, and it
     * would validate against the spec's own validation model rather than the one the role asked for.
     */
    @Bean("openAiCompatibleCredentialLlmServiceFactory")
    @ConditionalOnClass(OpenAiCompatibleModelFactory::class)
    @ConditionalOnMissingBean(name = ["openAiCompatibleCredentialLlmServiceFactory"])
    fun openAiCompatibleCredentialLlmServiceFactory() = CredentialLlmServiceFactory { credential, model ->
        OpenAiCompatibleModelFactory.endpointFor(credential.provider)?.let { endpoint ->
            OpenAiCompatibleModelFactory(baseUrl = endpoint.baseUrl, apiKey = credential.apiKey)
                .openAiCompatibleLlm(
                    model = model,
                    pricingModel = PricingModel.ALL_YOU_CAN_EAT,
                    provider = endpoint.provider,
                    knowledgeCutoffDate = null,
                )
        }
    }
}
