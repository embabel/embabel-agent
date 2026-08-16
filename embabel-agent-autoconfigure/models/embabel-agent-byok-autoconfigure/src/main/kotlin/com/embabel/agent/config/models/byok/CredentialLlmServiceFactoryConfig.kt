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
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.common.ai.model.CredentialLlmServiceFactory
import com.embabel.common.ai.model.PricingModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(AnthropicModelFactory::class)
class AnthropicCredentialLlmServiceFactoryConfig {

    @Bean
    @ConditionalOnMissingBean(name = ["anthropicCredentialLlmServiceFactory"])
    fun anthropicCredentialLlmServiceFactory() = CredentialLlmServiceFactory { credential, model ->
        if (!credential.provider.equals(AnthropicModels.PROVIDER, ignoreCase = true)) null
        else AnthropicModelFactory(apiKey = credential.apiKey).build(model)
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OpenAiCompatibleModelFactory::class)
class OpenAiCredentialLlmServiceFactoryConfig {

    @Bean
    @ConditionalOnMissingBean(name = ["openAiCredentialLlmServiceFactory"])
    fun openAiCredentialLlmServiceFactory() = CredentialLlmServiceFactory { credential, model ->
        if (!credential.provider.equals(OpenAiModels.PROVIDER, ignoreCase = true)) null
        else OpenAiCompatibleModelFactory(baseUrl = null, apiKey = credential.apiKey)
            .openAiCompatibleLlm(
                model = model,
                pricingModel = PricingModel.ALL_YOU_CAN_EAT,
                provider = OpenAiModels.PROVIDER,
                knowledgeCutoffDate = null,
            )
    }
}
