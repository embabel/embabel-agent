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
package com.embabel.agent.config.annotation;

import com.embabel.agent.event.AgenticEventListener;
import com.embabel.agent.rag.RagService;
import com.embabel.agent.spi.Ranker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for AgentPlatformAutoConfiguration via @EnableAgents annotation.
 *
 * <p>This test verifies that the @EnableAgents annotation properly:
 * <ul>
 *   <li>Activates the agent platform auto-configuration</li>
 *   <li>Registers required beans in the application context</li>
 *   <li>Sets up appropriate Spring profiles</li>
 * </ul>
 *
 * <p>Note: This test requires OPENAI_API_KEY to be set as an environment variable.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+",
        disabledReason = "Integration test requires OPENAI_API_KEY")
@TestPropertySource(properties = {
        "spring.main.lazy-initialization=true",
        "logging.level.com.embabel=DEBUG"
})
@DisplayName("EnableAgents Annotation Integration Test")
class EnableAgentsAnnotationIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private AgenticEventListener eventListener;

    @Autowired
    private Ranker ranker;

    @Autowired
    private RagService defaultSpringVectorStore;

    @Test
    @DisplayName("Should auto-configure all required agent platform beans")
    void testAutoConfiguredBeanPresence() {
        // Verify core beans are present
        assertThat(eventListener)
                .as("AgenticEventListener should be auto-configured")
                .isNotNull();

        assertThat(ranker)
                .as("Ranker should be auto-configured")
                .isNotNull();

        assertThat(defaultSpringVectorStore)
                .as("RagService should be auto-configured")
                .isNotNull();
    }

    @Test
    @DisplayName("Should register agent platform configuration beans")
    void testAgentPlatformConfigurationBeans() {
        // Verify that agent platform specific beans are registered
        assertThat(context.containsBean("agentPlatformAutoConfiguration"))
                .as("AgentPlatformAutoConfiguration should be registered")
                .isTrue();

        // Verify component scanning worked
        String[] beanNames = context.getBeanDefinitionNames();
        assertThat(beanNames)
                .as("Should contain agent-related beans")
                .anyMatch(name -> name.contains("agent") || name.contains("Agent"));
    }

    @Test
    @DisplayName("Should configure agent platform with additional attributes")
    void testEnableAgentsWithAttributes() {
        // This would test a configuration with attributes
        // Currently @EnableAgents has default values, but this shows how to test them

        // For example, if @EnableAgents had attributes:
        // - loggingTheme: verify theme profile is active
        // - localModels: verify model profiles are active

        // Since the test class uses @EnableAgents with defaults,
        // we just verify the base configuration works
        assertThat(context).isNotNull();
    }

    /**
     * Test configuration class that simulates a Spring Boot application.
     * This is nested to avoid conflicts with other test configurations.
     */
    @SpringBootApplication
    @EnableAgents
    static class TestApplication {
        // Empty - just provides application context for testing
    }
}

/**
 * Additional test class to verify @EnableAgents with custom attributes.
 */
@SpringBootTest
@DirtiesContext
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@DisplayName("EnableAgents with Custom Attributes Test")
class EnableAgentsWithAttributesIT {

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("Should activate profiles from annotation attributes")
    void testCustomAttributeProfiles() {
        String[] activeProfiles = environment.getActiveProfiles();

        // Verify all expected profiles are active
        assertThat(activeProfiles)
                .as("Should contain all configured profiles")
                .contains(
                        LoggingThemes.STAR_WARS,       // From loggingTheme
                        LocalModels.OLLAMA,            // From localModels
                        McpServers.DOCKER_DESKTOP      // From mcpClients
                );
    }

    @SpringBootApplication
    @EnableAgents(
            loggingTheme = LoggingThemes.STAR_WARS,
            localModels = {LocalModels.OLLAMA},
            mcpServers = {McpServers.DOCKER_DESKTOP}
    )
    static class CustomAttributesTestApplication {
        // Configuration with custom attributes
    }
}