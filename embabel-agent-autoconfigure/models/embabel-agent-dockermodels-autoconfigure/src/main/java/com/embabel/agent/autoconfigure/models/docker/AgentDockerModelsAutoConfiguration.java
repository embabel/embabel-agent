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
package com.embabel.agent.autoconfigure.models.docker;

import com.embabel.agent.config.models.docker.DockerLocalModelsConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Import;

/**
 * Autoconfiguration for Docker AI models in the Embabel Agent system.
 * <p>
 * This class serves as a Spring Boot autoconfiguration entry point that:
 * - Scans for configuration properties in the "com.embabel.agent" package
 * - Imports the [Docker] configuration to register model beans
 * <p>
 * The configuration is activated by starter dependencies that include Docker model support.
 */
@AutoConfiguration
@AutoConfigureBefore(name = {"com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration"})
@Import(DockerLocalModelsConfig.class)
public class AgentDockerModelsAutoConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(AgentDockerModelsAutoConfiguration.class);

    @PostConstruct
    public void logEvent() {
        logger.info("AgentOllamaAutoConfiguration about to proceed...");
    }
}
