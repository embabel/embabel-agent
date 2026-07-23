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
package com.embabel.agent.autoconfigure.mcpserver.security;

import com.embabel.agent.mcpserver.security.SecureAgentToolAspect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that the secured MCP server auto-configuration contributes the expected Spring Security infrastructure when the servlet and OAuth2 pieces are present.
 */
class AgentMcpServerSecurityAutoConfigurationTest {

   /**
    * Servlet web context with the MVC and JWT infrastructure that the production security filter chain expects when it builds MVC request matchers and JWT auth.
    */
   private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(
                   SecurityAutoConfiguration.class,
                   OAuth2ResourceServerAutoConfiguration.class,
                   AgentMcpServerAutoConfiguration.class
           ))
           .withBean("mvcHandlerMappingIntrospector", HandlerMappingIntrospector.class, HandlerMappingIntrospector::new)
           .withBean(JwtDecoder.class, () -> mock(JwtDecoder.class));

   /**
    * Confirms that the auto-configuration creates the method-security handler, aspect, JWT converter, and servlet security filter chain that secure MCP endpoints.
    */
   @Test
   void registersSecurityBeans() {
      // Act
      contextRunner.run(context -> {
         // Assert
         assertThat(context).hasSingleBean(MethodSecurityExpressionHandler.class);
         assertThat(context).hasSingleBean(SecureAgentToolAspect.class);
         assertThat(context).hasSingleBean(JwtAuthenticationConverter.class);
         assertThat(context).hasSingleBean(SecurityFilterChain.class);
      });
   }
}
