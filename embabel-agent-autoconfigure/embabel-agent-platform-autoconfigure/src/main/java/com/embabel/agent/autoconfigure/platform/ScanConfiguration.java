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

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;


/**
 * Spring Boot configuration class for component and configuration properties scanning.
 * <p>
 * Scans the following base packages for Spring components and configuration properties:
 * <ul>
 *   <li>com.embabel.agent</li>
 *   <li>com.embabel.example</li>
 * </ul>
 * This enables automatic bean registration and property binding for classes in these packages.
 */
@ConfigurationPropertiesScan(
        basePackages = {
                "com.embabel.agent.api",
                "com.embabel.agent.core",
                "com.embabel.agent.experimental",
                "com.embabel.agent.prompt",
                "com.embabel.agent.spi",
                "com.embabel.agent.test",
                "com.embabel.agent.tools",
                "com.embabel.agent.web",
                //Scan MCP Packages, this one should be moved over to MCP Module later
                "com.embabel.agent.mcpserver",
                //Scan RAG Packages, this one should be moved over to RAG Module later
                "com.embabel.agent.rag",
        }
)
@ComponentScan(
        basePackages = {
                //Scan Agent Framework Core Packages
                //This can stay here, this is the main autoconfigure module for the Agent Platform
                "com.embabel.agent.api",
                "com.embabel.agent.core",
                "com.embabel.agent.experimental",
                "com.embabel.agent.prompt",
                "com.embabel.agent.spi",
                "com.embabel.agent.test",
                "com.embabel.agent.tools",
                "com.embabel.agent.web",
                //Scan MCP Packages, this one should be moved over to MCP Module later
                "com.embabel.agent.mcpserver",
                //Scan RAG Packages, this one should be moved over to RAG Module later
                "com.embabel.agent.rag",
        }
)
@Configuration
public class ScanConfiguration {

    final private Logger logger = LoggerFactory.getLogger(ScanConfiguration.class);

    @PostConstruct
    public void logEvent() {
        logger.info("ComponentConfiguration initialized: Scanning com.embabel.agent and com.embabel.example packages.");
    }

}
