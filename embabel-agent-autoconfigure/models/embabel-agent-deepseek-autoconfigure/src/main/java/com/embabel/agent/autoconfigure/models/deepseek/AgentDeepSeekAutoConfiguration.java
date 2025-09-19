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
package com.embabel.agent.autoconfigure.models.deepseek;

import com.embabel.agent.config.models.deepseek.DeepSeekModelsConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Import;

/**
 * Autoconfiguration for DeepSeek AI models in the Embabel Agent system.
 * <p>
 * This class serves as a Spring Boot autoconfiguration entry point that:
 * - Scans for configuration properties in the "com.embabel.agent" package
 * - Imports the [DeepSeekModels] configuration to register DeepSeek model beans
 * <p>
 * The configuration is automatically activated when the DeepSeek models
 * dependencies are present on the classpath.
 */
@AutoConfiguration
@AutoConfigureBefore(name = {"com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration"})
@Import(DeepSeekModelsConfig.class)
public class AgentDeepSeekAutoConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(AgentDeepSeekAutoConfiguration.class);

    @PostConstruct
    public void logEvent() {
        logger.info("AgentDeepseekAutoConfiguration about to proceed...");
    }
}
