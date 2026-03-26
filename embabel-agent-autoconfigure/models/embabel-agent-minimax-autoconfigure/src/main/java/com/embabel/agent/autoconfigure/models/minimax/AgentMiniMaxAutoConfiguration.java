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
package com.embabel.agent.autoconfigure.models.minimax;

import com.embabel.agent.config.models.minimax.MiniMaxModelsConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Import;

/**
 * Autoconfiguration for MiniMax AI models in the Embabel Agent system.
 * <p>
 * This class serves as a Spring Boot autoconfiguration entry point that:
 * - Imports the {@link MiniMaxModelsConfig} configuration to register MiniMax model beans
 * <p>
 * The configuration is automatically activated when the MiniMax
 * dependencies are present on the classpath and the MINIMAX_API_KEY
 * environment variable is set.
 */
@AutoConfiguration
@AutoConfigureBefore(name = {"com.embabel.agent.autoconfigure.platform.AgentPlatformAutoConfiguration"})
@Import(MiniMaxModelsConfig.class)
public class AgentMiniMaxAutoConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(AgentMiniMaxAutoConfiguration.class);

    @PostConstruct
    public void logEvent() {
        logger.info("AgentMiniMaxAutoConfiguration about to proceed...");
    }
}
