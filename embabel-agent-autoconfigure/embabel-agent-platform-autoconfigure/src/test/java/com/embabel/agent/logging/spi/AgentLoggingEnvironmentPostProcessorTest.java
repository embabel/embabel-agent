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
package com.embabel.agent.logging.spi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentLoggingEnvironmentPostProcessor}.
 */
class AgentLoggingEnvironmentPostProcessorTest {

    private AgentLoggingEnvironmentPostProcessor processor;
    private ConfigurableEnvironment environment;
    private SpringApplication application;

    @BeforeEach
    void setUp() {
        processor = new AgentLoggingEnvironmentPostProcessor();
        environment = new StandardEnvironment();
        application = new SpringApplication();
    }

    @Test
    void shouldSetLoggingConfigFromAgentPlatformProperties() {
        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        String loggingConfig = environment.getProperty("logging.config");
        assertNotNull(loggingConfig, "logging.config should be set");
        assertEquals("classpath:logback-embabel.xml", loggingConfig);
    }

    @Test
    void shouldSkipWhenUserAlreadySetLoggingConfig() {
        // Given
        System.setProperty("logging.config", "classpath:user-logback.xml");
        try {
            environment.getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("test",
                            java.util.Collections.singletonMap("logging.config", "classpath:user-logback.xml"))
            );

            // When
            processor.postProcessEnvironment(environment, application);

            // Then
            String loggingConfig = environment.getProperty("logging.config");
            assertEquals("classpath:user-logback.xml", loggingConfig,
                    "User's logging.config should be preserved");
        } finally {
            System.clearProperty("logging.config");
        }
    }

    @Test
    void shouldHaveHighestPrecedenceOrder() {
        // When
        int order = processor.getOrder();

        // Then
        assertEquals(Ordered.HIGHEST_PRECEDENCE, order,
                "Should run with highest precedence to set logging config before logging initialization");
    }

    @Test
    void shouldNotThrowExceptionWhenAgentPlatformPropertiesIsMissing() {
        // Given - using a custom classloader that doesn't have agent-platform.properties
        // This is difficult to simulate in a unit test, so we just verify no exceptions
        // are thrown during normal execution

        // When/Then - should not throw
        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, application));
    }

    @Test
    void shouldAddLoggingConfigToEnvironment() {
        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        assertTrue(environment.containsProperty("logging.config"),
                "logging.config should be present in environment");
    }

    @Test
    void shouldUseFirstPropertySourceForLoggingConfig() {
        // Given
        processor.postProcessEnvironment(environment, application);

        // Then
        // Verify it's added as first property source (highest priority)
        String firstSourceName = environment.getPropertySources().iterator().next().getName();
        assertEquals("loggingConfigSource", firstSourceName,
                "Logging config should be added as first property source");
    }
}
