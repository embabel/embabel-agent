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

import com.embabel.agent.config.annotation.EnableAgentShell;
import com.embabel.agent.config.annotation.EnableAgents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbabelEnvironmentProcessorTest {

    private EmbabelEnvironmentProcessor processor;
    private ConfigurableEnvironment environment;
    private SpringApplication application;
    private String originalProfilesProperty;

    @BeforeEach
    void setUp() {
        processor = new EmbabelEnvironmentProcessor();
        environment = new MockEnvironment();
        application = mock(SpringApplication.class);

        // Save original system property
        originalProfilesProperty = System.getProperty("spring.profiles.active");
    }

    @AfterEach
    void tearDown() {
        // Restore original system property
        if (originalProfilesProperty != null) {
            System.setProperty("spring.profiles.active", originalProfilesProperty);
        } else {
            System.clearProperty("spring.profiles.active");
        }
    }

    @Test
    @DisplayName("Should activate shell profile when @EnableAgentShell is present")
    void testEnableAgentShellActivatesShellProfile() {
        // Given
        @EnableAgentShell
        class TestApp {
        }

        when(application.getAllSources()).thenReturn(new HashSet<>(List.of(TestApp.class)));

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        assertThat(System.getProperty("spring.profiles.active")).contains("shell");
        assertThat(environment.getActiveProfiles()).contains("shell");
    }

    @Test
    @DisplayName("Should activate starwars profile when loggingTheme is starwars")
    void testStarWarsThemeActivatesProfile() {
        // Given
        @EnableAgentShell(loggingTheme = "starwars")
        class TestApp {
        }

        when(application.getAllSources()).thenReturn(new HashSet<>(List.of(TestApp.class)));

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        String activeProfiles = System.getProperty("spring.profiles.active");
        assertThat(activeProfiles).contains("shell");
        assertThat(activeProfiles).contains("starwars");
        assertThat(environment.getActiveProfiles()).contains("shell", "starwars");
    }

    @Test
    @DisplayName("Should activate severance profile when loggingTheme is severance")
    void testSeveranceThemeActivatesProfile() {
        // Given
        @EnableAgentShell(loggingTheme = "severance")
        class TestApp {
        }

        when(application.getAllSources()).thenReturn(new HashSet<>(List.of(TestApp.class)));

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        String activeProfiles = System.getProperty("spring.profiles.active");
        assertThat(activeProfiles).contains("shell");
        assertThat(activeProfiles).contains("severance");
        assertThat(environment.getActiveProfiles()).contains("shell", "severance");
    }

    @Test
    @DisplayName("Should not activate theme profile when loggingTheme is empty")
    void testEmptyThemeDoesNotActivateProfile() {
        // Given
        @EnableAgentShell(loggingTheme = "")
        class TestApp {
        }

        when(application.getAllSources()).thenReturn(new HashSet<>(List.of(TestApp.class)));

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        String activeProfiles = System.getProperty("spring.profiles.active");
        assertThat(activeProfiles).contains("shell");
        assertThat(activeProfiles).doesNotContain("starwars", "severance");
    }

    @Test
    @DisplayName("Should handle unknown theme gracefully")
    void testUnknownThemeIsIgnored() {
        // Given
        @EnableAgentShell(loggingTheme = "unknown")
        class TestApp {
        }

        when(application.getAllSources()).thenReturn(new HashSet<>(List.of(TestApp.class)));

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        String activeProfiles = System.getProperty("spring.profiles.active");
        assertThat(activeProfiles).contains("shell");
        assertThat(activeProfiles).doesNotContain("unknown");
    }

    @Test
    @DisplayName("Should preserve existing profiles")
    void testPreservesExistingProfiles() {
        // Given
        System.setProperty("spring.profiles.active", "existing,profiles");

        @EnableAgentShell(loggingTheme = "starwars")
        class TestApp {
        }

        when(application.getAllSources()).thenReturn(new HashSet<>(List.of(TestApp.class)));

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        String activeProfiles = System.getProperty("spring.profiles.active");
        assertThat(activeProfiles).contains("existing");
        assertThat(activeProfiles).contains("profiles");
        assertThat(activeProfiles).contains("shell");
        assertThat(activeProfiles).contains("starwars");
    }

    @Test
    @DisplayName("Should handle @EnableAgents with custom values")
    void testEnableAgentsWithCustomValues() {
        // Given
        @EnableAgents({"custom1", "custom2"})
        class TestApp {
        }

        when(application.getAllSources()).thenReturn(new HashSet<>(List.of(TestApp.class)));

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        String activeProfiles = System.getProperty("spring.profiles.active");
        assertThat(activeProfiles).contains("custom1");
        assertThat(activeProfiles).contains("custom2");
    }

    @Test
    @DisplayName("Should handle multiple source classes")
    void testMultipleSourceClasses() {
        // Given
        @EnableAgentShell(loggingTheme = "starwars")
        class TestApp1 {
        }

        class TestApp2 {
        } // No annotation

        when(application.getAllSources()).thenReturn(
                new HashSet<>(Arrays.asList(TestApp1.class, TestApp2.class))
        );

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        assertThat(environment.getActiveProfiles()).contains("shell", "starwars");
    }

    @Test
    @DisplayName("Should have highest precedence order")
    void testHasHighestPrecedence() {
        assertThat(processor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("Should handle non-class sources gracefully")
    void testNonClassSources() {
        // Given
        when(application.getAllSources()).thenReturn(
                new HashSet<>(Arrays.asList("not-a-class", new Object()))
        );

        // When/Then - should not throw exception
        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, application));
    }
}