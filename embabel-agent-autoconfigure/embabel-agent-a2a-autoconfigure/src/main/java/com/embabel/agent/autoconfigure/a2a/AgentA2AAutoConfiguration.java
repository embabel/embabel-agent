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
package com.embabel.agent.autoconfigure.a2a;

import com.embabel.agent.a2a.server.AgentCardHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Auto-configuration for Embabel Agent A2A (Agent-to-Agent) Protocol.
 *
 * <p>Automatically activates when:
 * <ul>
 *   <li>A2A classes are on the classpath</li>
 *   <li>Running in a servlet web application</li>
 * </ul>
 *
 * <p>This configuration enables:
 * <ul>
 *   <li>Agent card endpoint: {@code /{path}/.well-known/agent.json}</li>
 *   <li>JSON-RPC endpoint: {@code /{path}}</li>
 * </ul>
 *
 * @since 0.3.1
 */
@AutoConfiguration
@ConditionalOnClass({
    AgentCardHandler.class,
    RequestMappingHandlerMapping.class
})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ComponentScan(basePackages = "com.embabel.agent.a2a")
public class AgentA2AAutoConfiguration {
}
