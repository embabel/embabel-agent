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
package com.embabel.agent.autoconfigure.models.openai;

import com.embabel.agent.config.models.OpenAiModels;
import com.embabel.agent.config.models.openai.OpenAiModelsConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * Autoconfiguration for OpenAI models in the Embabel Agent system.
 * <p>
 * This class serves as a Spring Boot autoconfiguration entry point that:
 * - Scans for configuration properties in the "com.embabel.agent" package
 * - Imports the {@link OpenAiModels} configuration to register OpenAI model beans
 * <p>
 * The configuration is automatically activated when the OpenAI models
 * dependencies are present on the classpath.
 */
@AutoConfiguration
@ConfigurationPropertiesScan(
        basePackages = {
                "com.embabel.agent"
        }
)
@Import(OpenAiModelsConfig.class)
public class AgentOpenAiAutoConfiguration {
}
