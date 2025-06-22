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

import com.embabel.agent.config.annotation.spi.EmbabelEnvironmentProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Additional test cases for edge scenarios
 */
class LoggingThemeParameterizedTest {

    private EmbabelEnvironmentProcessor processor;
    private ConfigurableEnvironment environment;
    private SpringApplication application;
    private String originalProfilesProperty;

    @BeforeEach
    void setUp() {
        processor = new EmbabelEnvironmentProcessor();
        environment = spy(new MockEnvironment());
        application = mock(SpringApplication.class);

        originalProfilesProperty = System.getProperty("spring.profiles.active");
        System.clearProperty("spring.profiles.active");
    }

    @AfterEach
    void tearDown() {
        if (originalProfilesProperty != null) {
            System.setProperty("spring.profiles.active", originalProfilesProperty);
        } else {
            System.clearProperty("spring.profiles.active");
        }
    }

    @Test
    @DisplayName("Should handle @EnableAgentShell alone (inherits shell from @EnableAgents)")
    void testEnableAgentShellAlone() {
        // Given - Only @EnableAgentShell, no explicit @EnableAgents
        @EnableAgentShell(loggingTheme = "starwars")
        class TestApp {}

        when(application.getAllSources()).thenReturn(new HashSet<>(Arrays.asList(TestApp.class)));

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        verify(environment).addActiveProfile("shell"); // From inherited @EnableAgents("shell")
        verify(environment).addActiveProfile("starwars"); // From loggingTheme

        String profiles = System.getProperty("spring.profiles.active");
        assertThat(profiles).isEqualTo("shell,starwars");
    }

    @Test
    @DisplayName("Should handle explicit @EnableAgents overriding inherited value")
    void testExplicitEnableAgentsOverride() {
        // Given - Both annotations, @EnableAgents with explicit values
        @EnableAgents({"custom1", "custom2"})
        @EnableAgentShell(loggingTheme = "severance")
        class TestApp {}

        when(application.getAllSources()).thenReturn(new HashSet<>(Arrays.asList(TestApp.class)));

        // When
        processor.postProcessEnvironment(environment, application);

        // Then - "shell" is NOT included because explicit @EnableAgents overrides it
        verify(environment).addActiveProfile("custom1");
        verify(environment).addActiveProfile("custom2");
        verify(environment).addActiveProfile("severance");
        verify(environment, never()).addActiveProfile("shell");

        String profiles = System.getProperty("spring.profiles.active");
        assertThat(profiles).isEqualTo("custom1,custom2,severance");
    }

    @Test
    @DisplayName("Should handle @EnableAgents with empty array")
    void testEnableAgentsEmptyArray() {
        // Given
        @EnableAgents({})
        @EnableAgentShell(loggingTheme = "starwars")
        class TestApp {}

        when(application.getAllSources()).thenReturn(new HashSet<>(Arrays.asList(TestApp.class)));

        // When
        processor.postProcessEnvironment(environment, application);

        // Then - Only starwars theme profile should be active
        verify(environment).addActiveProfile("starwars");
        verify(environment, never()).addActiveProfile("shell");

        String profiles = System.getProperty("spring.profiles.active");
        assertThat(profiles).isEqualTo("starwars");
    }

    @Test
    @DisplayName("Should handle only @EnableAgents without @EnableAgentShell")
    void testOnlyEnableAgents() {
        // Given
        @EnableAgents({"profile1", "profile2"})
        class TestApp {}

        when(application.getAllSources()).thenReturn(new HashSet<>(Arrays.asList(TestApp.class)));

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        verify(environment).addActiveProfile("profile1");
        verify(environment).addActiveProfile("profile2");
        verify(environment, never()).addActiveProfile("starwars");
        verify(environment, never()).addActiveProfile("severance");

        String profiles = System.getProperty("spring.profiles.active");
        assertThat(profiles).isEqualTo("profile1,profile2");
    }

}