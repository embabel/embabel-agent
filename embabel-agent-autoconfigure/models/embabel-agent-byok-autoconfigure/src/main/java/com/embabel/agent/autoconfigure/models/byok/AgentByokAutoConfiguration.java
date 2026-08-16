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
package com.embabel.agent.autoconfigure.models.byok;

import com.embabel.agent.config.models.byok.AnthropicCredentialLlmServiceFactoryConfig;
import com.embabel.agent.config.models.byok.OpenAiCredentialLlmServiceFactoryConfig;
import com.embabel.agent.config.models.byok.SetupRequiredEmbeddingConfig;
import com.embabel.agent.config.models.byok.SetupRequiredLlmConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Import;

/**
 * Autoconfiguration for Bring Your Own Key deployments.
 * <p>
 * Unlike the provider autoconfigurations, this registers no real models and requires no API key.
 * It contributes the {@code setup-required} placeholders and credential-backed LLM service
 * factories for supported providers on the classpath. The placeholders let a deployment holding
 * no provider key start up and resolve
 * {@code embabel.models.default-llm} and {@code embabel.models.default-embedding-model}. Keys can
 * arrive at runtime through role resolution or an explicitly built service.
 * <p>
 * The embedding placeholder never reports a dimension: a vector index built at a guessed dimension
 * would accept writes and disagree with the real model later, so anything provisioning one must
 * check for {@code PlaceholderEmbeddingService} and wait.
 * <p>
 * Runs before the platform autoconfiguration so the placeholder bean exists by the time the model
 * provider is built.
 */
@AutoConfiguration
@AutoConfigureBefore(name = {"com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration"})
@Import({
        SetupRequiredLlmConfig.class,
        SetupRequiredEmbeddingConfig.class,
        AnthropicCredentialLlmServiceFactoryConfig.class,
        OpenAiCredentialLlmServiceFactoryConfig.class
})
public class AgentByokAutoConfiguration {
}
