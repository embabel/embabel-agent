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
package com.embabel.agent
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication
@ConfigurationPropertiesScan(
    basePackages = [
        "com.embabel.agent.api",
        "com.embabel.agent.core",
        "com.embabel.agent.experimental",
        "com.embabel.agent.prompt",
        "com.embabel.agent.spi",
        "com.embabel.agent.test",
        "com.embabel.agent.tools",
        "com.embabel.agent.web",
        "com.embabel.example",
        //Scan Agent Shell, this one should be moved over to Shell Module later
        "com.embabel.agent.shell",
        //Scan MCP Packages, this one should be moved over to MCP Module later
        "com.embabel.agent.mcpserver",

    ]
)
@ComponentScan(
    basePackages = [
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
        "com.embabel.example",
        //Scan Agent Shell, this one should be moved over to Shell Module later
        "com.embabel.agent.shell",
        //Scan MCP Packages, this one should be moved over to MCP Module later
        "com.embabel.agent.mcpserver",
    ]
)
class AgentTestApplication {}
