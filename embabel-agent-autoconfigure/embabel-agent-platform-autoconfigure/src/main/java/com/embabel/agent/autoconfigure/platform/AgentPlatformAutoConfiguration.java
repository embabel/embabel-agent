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
package com.embabel.agent.autoconfigure.platform;


import com.embabel.agent.config.AgentPlatformConfiguration;
import com.embabel.agent.config.ToolGroupsConfiguration;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Bootstraps Agent Platform Configuration, Tools Configuration, and Rag Service Configuration
 */
@AutoConfiguration
@Import({ScanConfiguration.class, AgentPlatformConfiguration.class, ToolGroupsConfiguration.class,})
public class AgentPlatformAutoConfiguration {
    final private static Logger logger = LoggerFactory.getLogger(AgentPlatformAutoConfiguration.class);

    static {
        logger.info("AgentPlatformAutoConfiguration has been initialized.");
    }

    @PostConstruct
    public void logEvent() {
        logger.info("AgentPlatformAutoConfiguration about to be processed...");
    }
}
