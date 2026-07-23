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
package com.embabel.agent.autoconfigure.a2a;

import com.embabel.agent.a2a.server.AgentCardHandler;
import com.embabel.agent.a2a.server.support.A2AEndpointRegistrar;
import com.embabel.agent.a2a.server.support.A2AStreamingHandler;
import com.embabel.agent.a2a.server.support.AutonomyA2ARequestHandler;
import com.embabel.agent.api.common.autonomy.Autonomy;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.core.AgentPlatform;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies that the A2A auto-configuration only activates in a servlet web application and that, when active, it contributes the expected endpoint wiring beans.
 */
class AgentA2AAutoConfigurationTest {

   /**
    * Non-web baseline used to prove that the servlet-only condition prevents the A2A beans from being created.
    */
   private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentA2AAutoConfiguration.class));

   /**
    * Servlet web context with mocked collaborators so the auto-configuration can build its handler and endpoint registrar without pulling in the full platform.
    */
   private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
           .withConfiguration(AutoConfigurations.of(AgentA2AAutoConfiguration.class))
           .withBean(AgentPlatform.class, () -> mock(AgentPlatform.class))
           .withBean(Autonomy.class, () -> mock(Autonomy.class))
           .withBean(AgenticEventListener.class, () -> mock(AgenticEventListener.class))
           .withBean(A2AStreamingHandler.class, () -> mock(A2AStreamingHandler.class))
           .withBean(AutonomyA2ARequestHandler.class,
                     () -> new AutonomyA2ARequestHandler(
                             mock(Autonomy.class),
                             mock(AgenticEventListener.class),
                             mock(A2AStreamingHandler.class)))
           .withBean(RequestMappingHandlerMapping.class, () -> mock(RequestMappingHandlerMapping.class))
           .withBean(ObjectMapper.class, ObjectMapper::new);

   /**
    * Confirms that the auto-configuration stays inactive in a plain application context. This checks the servlet web-application guard instead of only inspecting annotations.
    */
   @Test
   void doesNotActivateOutsideServletWebApplication() {
      // Act
      contextRunner.run(context -> {
         // Assert
         assertThat(context).doesNotHaveBean(AgentCardHandler.class);
         assertThat(context).doesNotHaveBean(A2AEndpointRegistrar.class);
      });
   }

   /**
    * Confirms that the servlet web context activates the configuration and registers both the card handler bean and the registrar that exposes the A2A endpoints.
    */
   @Test
   void registersA2aBeansInServletWebApplication() {
      // Act
      webContextRunner.run(context -> {
         // Assert
         assertThat(context).hasSingleBean(AgentCardHandler.class);
         assertThat(context).hasSingleBean(A2AEndpointRegistrar.class);
      });
   }
}
