/*
 * Copyright 2024-2025 Embabel Pty Ltd.
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
package com.embabel.agent.autoconfigure.models.mistralai;

import com.embabel.agent.config.models.mistralai.MistralAiModelsConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Import;

/**
 * Autoconfiguration for Mistral AI models in the Embabel Agent system.
 * <p>
 * This class serves as a Spring Boot autoconfiguration entry point that:
 * - Imports the [MistralAIModelsConfig] configuration to dynamically register Mistral AI model beans
 */
@AutoConfiguration
@AutoConfigureBefore(name = {"com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration"})
@Import(MistralAiModelsConfig.class)
public class AgentMistralAiAutoConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(AgentMistralAiAutoConfiguration.class);

    @PostConstruct
    public void logEvent() {
        logger.info("AgentMistralAiAutoConfiguration about to proceed...");
    }
}
