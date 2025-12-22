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
package com.embabel.agent.config.annotation.spi;

import com.embabel.agent.config.annotation.EnableAgents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnvironmentPostProcessorTest {

    private EnvironmentPostProcessor processor;
    private MockEnvironment environment;
    private SpringApplication application;
    private String originalProfilesProperty;

    @BeforeEach
    void setUp() {
        processor = new EnvironmentPostProcessor();
        environment = new MockEnvironment();
        application = mock(SpringApplication.class);

        // Save and clear system property
        originalProfilesProperty = System.getProperty("spring.profiles.active");
        System.clearProperty("spring.profiles.active");
    }

    @AfterEach
    void tearDown() {
        // Restore system property
        if (originalProfilesProperty != null) {
            System.setProperty("spring.profiles.active", originalProfilesProperty);
        } else {
            System.clearProperty("spring.profiles.active");
        }
    }

    @Test
    void testEnableAgentsWithLoggingTheme() {
        // Given
        @EnableAgents(loggingTheme = "starwars")
        class TestApp {
        }
        when(application.getAllSources()).thenReturn(Set.of(TestApp.class));

        assertThat(environment.containsProperty(EnvironmentPostProcessor.LOGGING_THEME_PROPERTY))
                .isFalse(); //should not be there yet

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        assertThat(environment.containsProperty(EnvironmentPostProcessor.LOGGING_THEME_PROPERTY))
                .isTrue();
        assertThat(environment.getProperty(EnvironmentPostProcessor.LOGGING_THEME_PROPERTY))
                .isEqualTo("starwars");
    }


    @Test
    void testHighestPrecedenceOrder() {
        assertThat(processor.getOrder()).isEqualTo(Integer.MIN_VALUE);
    }


}