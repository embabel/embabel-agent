/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.autoconfigure.models.anthropic;

import com.embabel.agent.config.models.anthropic.AnthropicModelsConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * Autoconfiguration for Anthropic AI models in the Embabel Agent system.
 * <p>
 * This class serves as a Spring Boot autoconfiguration entry point that:
 * - Scans for configuration properties in the "com.embabel.agent" package
 * - Imports the [AnthropicModels] configuration to register Anthropic model beans
 * <p>
 * The configuration is automatically activated when the Anthropic models
 * dependencies are present on the classpath.
 */
@AutoConfiguration
@ConfigurationPropertiesScan(
        basePackages = {
                "com.embabel.agent"
        }
)
@Import(AnthropicModelsConfig.class)
public class AgentAnthropicAutoConfiguration {
}
