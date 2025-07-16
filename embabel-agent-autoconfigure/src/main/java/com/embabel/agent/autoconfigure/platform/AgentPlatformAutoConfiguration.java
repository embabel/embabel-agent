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
import com.embabel.agent.config.RuntimeHintsConfig;
import com.embabel.agent.config.RagServiceConfiguration;
import com.embabel.agent.config.ToolGroupsConfiguration;
import com.embabel.common.util.UtilRuntimeHints;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Bootstraps Agent Platform Configuration, Tools Configuration, and Rag Service Configuration
 */
@AutoConfiguration
@ConfigurationPropertiesScan(
        basePackages = {
                "com.embabel.agent"
        }
)
@ComponentScan(
        basePackages = {
                "com.embabel.agent"
        }
)
@ConditionalOnClass({AgentPlatformConfiguration.class, ToolGroupsConfiguration.class, RagServiceConfiguration.class})
@Import({AgentPlatformConfiguration.class, ToolGroupsConfiguration.class, RagServiceConfiguration.class})
@ImportRuntimeHints(UtilRuntimeHints.class)
public class AgentPlatformAutoConfiguration {
    final private Logger logger = LoggerFactory.getLogger(AgentPlatformAutoConfiguration.class);

    @PostConstruct
    public void logEvent() {
        logger.info("AgentPlatformAutoConfiguration about to be processed...");
    }
}
