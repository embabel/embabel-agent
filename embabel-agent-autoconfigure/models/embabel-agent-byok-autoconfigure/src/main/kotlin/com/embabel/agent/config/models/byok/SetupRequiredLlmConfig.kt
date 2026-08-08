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

import com.embabel.agent.spi.LlmService
import com.embabel.common.util.loggerFor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the [SetupRequiredLlm] placeholder so a deployment with no provider key can start.
 *
 * The bean is registered unconditionally rather than behind `@ConditionalOnMissingBean(LlmService)`.
 * Provider autoconfigurations do not declare their models as `@Bean`s — they call `registerSingleton`
 * imperatively from inside a `@Bean` method — so such a condition would be evaluated long before
 * those singletons exist and would always see none. Registering unconditionally and opting in
 * through `embabel.models.default-llm` avoids the ordering question entirely.
 *
 * An unused placeholder is harmless. Every selection path reaches a model by naming it — by
 * name, by role, or as `embabel.models.default-llm` — and `auto` resolves to that same default.
 * So a deployment that does not name [SetupRequiredLlm.NAME] anywhere never resolves it, and
 * adding this starter alongside a real provider changes nothing.
 */
@Configuration(proxyBeanMethods = false)
class SetupRequiredLlmConfig {

    @Bean(SetupRequiredLlm.NAME)
    fun setupRequiredLlmService(): LlmService<*> {
        loggerFor<SetupRequiredLlmConfig>().info(
            """
            Registered placeholder LLM '{}'. Set embabel.models.default-llm={} to start with no provider key;
            supply a real key per request with PromptRunner.withLlmService(...).
            """.trimIndent(),
            SetupRequiredLlm.NAME,
            SetupRequiredLlm.NAME,
        )
        return SetupRequiredLlm.llmService()
    }
}
