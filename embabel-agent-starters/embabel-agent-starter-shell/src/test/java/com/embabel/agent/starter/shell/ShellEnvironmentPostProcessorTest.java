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
package com.embabel.agent.starter.shell;

import com.embabel.agent.config.annotation.EnableAgentShell;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;

import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShellEnvironmentPostProcessorTest {

    @Mock
    private ConfigurableEnvironment environment;

    @Mock
    private SpringApplication application;

    @Mock
    private MutablePropertySources propertySources;

    private ShellEnvironmentPostProcessor processor;

    @BeforeEach
    void suppressLogging() {
        Logger logger = (Logger) LoggerFactory.getLogger(ShellEnvironmentPostProcessor.class);
        logger.setLevel(Level.OFF);
    }

    @Test
    void shouldSkipProcessingWhenNoEnableAgentShellAnnotation() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(String.class); // Not annotated class
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        verify(propertySources, never()).addFirst(any());
    }

    @Test
    void shouldSkipProcessingWhenSourcesIsEmpty() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        when(application.getAllSources()).thenReturn(Collections.emptySet());
        when(environment.getPropertySources()).thenReturn(propertySources);

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        verify(propertySources, never()).addFirst(any());
    }

    @Test
    void shouldSkipProcessingWhenSourcesIsNull() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        when(application.getAllSources()).thenReturn(null);
        when(environment.getPropertySources()).thenReturn(propertySources);

        // When
        processor.postProcessEnvironment(environment, application);

        // Then - should not throw exception and should skip processing
        verify(propertySources, never()).addFirst(any());
    }

    @Test
    void shouldProcessEnvironmentWhenEnableAgentShellAnnotationPresent() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(TestClassWithAnnotation.class);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        AgentShellStarterProperties properties = createDefaultProperties();

        try (MockedStatic<Binder> binderMock = mockStatic(Binder.class)) {
            Binder binder = mock(Binder.class);
            BindResult<AgentShellStarterProperties> bindResult = mock(BindResult.class);

            binderMock.when(() -> Binder.get(environment)).thenReturn(binder);

            lenient().when(binder.bind(anyString(), any(Bindable.class))).thenReturn(bindResult);
            lenient().when(binder.bind(anyString(), eq(AgentShellStarterProperties.class))).thenReturn(bindResult);
            lenient().when(bindResult.isBound()).thenReturn(true);
            lenient().when(bindResult.get()).thenReturn(properties);
            lenient().when(bindResult.orElse(any())).thenReturn(properties);
            lenient().when(bindResult.orElseGet(any())).thenReturn(properties);

            // When
            processor.postProcessEnvironment(environment, application);

            // Then
            verify(propertySources).addFirst(any(MapPropertySource.class));
        }
    }

    @Test
    void shouldUseDefaultPropertiesWhenBindingFails() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(TestClassWithAnnotation.class);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        AgentShellStarterProperties defaultProperties = createDefaultProperties();

        try (MockedStatic<Binder> binderMock = mockStatic(Binder.class)) {
            Binder binder = mock(Binder.class);
            BindResult<AgentShellStarterProperties> bindResult = mock(BindResult.class);

            binderMock.when(() -> Binder.get(environment)).thenReturn(binder);

            lenient().when(binder.bind(anyString(), any(Bindable.class))).thenReturn(bindResult);
            lenient().when(binder.bind(anyString(), eq(AgentShellStarterProperties.class))).thenReturn(bindResult);
            lenient().when(bindResult.isBound()).thenReturn(false);
            lenient().when(bindResult.orElse(any())).thenReturn(defaultProperties);
            lenient().when(bindResult.orElseGet(any())).thenReturn(defaultProperties);

            // When
            processor.postProcessEnvironment(environment, application);

            // Then
            verify(propertySources).addFirst(any(MapPropertySource.class));
        }
    }

    @Test
    void shouldUseDefaultPropertiesWhenBindExceptionIsThrown() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(TestClassWithAnnotation.class);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        try (MockedStatic<Binder> binderMock = mockStatic(Binder.class)) {
            Binder binder = mock(Binder.class);

            binderMock.when(() -> Binder.get(environment)).thenReturn(binder);
            lenient().when(binder.bind(anyString(), any(Bindable.class)))
                    .thenThrow(new IllegalArgumentException("Invalid binding configuration"));
            lenient().when(binder.bind(anyString(), eq(AgentShellStarterProperties.class)))
                    .thenThrow(new IllegalArgumentException("Invalid binding configuration"));

            // When - should handle binding exceptions gracefully
            assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, application));

            // Then - should still add default properties
            verify(propertySources).addFirst(any(MapPropertySource.class));
        }
    }

    @Test
    void shouldUseDefaultPropertiesWhenGeneralExceptionIsThrown() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(TestClassWithAnnotation.class);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        try (MockedStatic<Binder> binderMock = mockStatic(Binder.class)) {
            Binder binder = mock(Binder.class);

            binderMock.when(() -> Binder.get(environment)).thenReturn(binder);
            lenient().when(binder.bind(anyString(), any(Bindable.class)))
                    .thenThrow(new IllegalStateException("Unexpected error"));
            lenient().when(binder.bind(anyString(), eq(AgentShellStarterProperties.class)))
                    .thenThrow(new IllegalStateException("Unexpected error"));

            // When - should handle general exceptions gracefully
            assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, application));

            // Then - should still add default properties
            verify(propertySources).addFirst(any(MapPropertySource.class));
        }
    }

    @Test
    void shouldSetCorrectPropertyValuesFromBoundProperties() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(TestClassWithAnnotation.class);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        AgentShellStarterProperties properties = new AgentShellStarterProperties();
        properties.setWebApplicationType("servlet");
        properties.getCommand().setExitEnabled(true);
        properties.getCommand().setQuitEnabled(true);
        properties.getInteractive().setEnabled(false);
        properties.getInteractive().setHistoryEnabled(false);

        try (MockedStatic<Binder> binderMock = mockStatic(Binder.class)) {
            Binder binder = mock(Binder.class);
            BindResult<AgentShellStarterProperties> bindResult = mock(BindResult.class);

            binderMock.when(() -> Binder.get(environment)).thenReturn(binder);

            lenient().when(binder.bind(anyString(), any(Bindable.class))).thenReturn(bindResult);
            lenient().when(binder.bind(anyString(), eq(AgentShellStarterProperties.class))).thenReturn(bindResult);
            lenient().when(bindResult.isBound()).thenReturn(true);
            lenient().when(bindResult.get()).thenReturn(properties);
            lenient().when(bindResult.orElse(any())).thenReturn(properties);
            lenient().when(bindResult.orElseGet(any())).thenReturn(properties);

            // When
            processor.postProcessEnvironment(environment, application);

            // Then
            ArgumentCaptor<MapPropertySource> captor = ArgumentCaptor.forClass(MapPropertySource.class);
            verify(propertySources).addFirst(captor.capture());

            MapPropertySource captured = captor.getValue();
            assertEquals("shellModeProperties", captured.getName());
            assertEquals("servlet", captured.getProperty("spring.main.web-application-type"));
            assertEquals(true, captured.getProperty("spring.shell.command.exit.enabled"));
            assertEquals(true, captured.getProperty("spring.shell.command.quit.enabled"));
            assertEquals(false, captured.getProperty("spring.shell.interactive.enabled"));
            assertEquals(false, captured.getProperty("spring.shell.history.enabled"));
        }
    }

    @Test
    void shouldSetCorrectPropertyValuesWithDefaultProperties() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(TestClassWithAnnotation.class);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        AgentShellStarterProperties defaultProperties = createDefaultProperties();

        try (MockedStatic<Binder> binderMock = mockStatic(Binder.class)) {
            Binder binder = mock(Binder.class);
            BindResult<AgentShellStarterProperties> bindResult = mock(BindResult.class);

            binderMock.when(() -> Binder.get(environment)).thenReturn(binder);

            lenient().when(binder.bind(anyString(), any(Bindable.class))).thenReturn(bindResult);
            lenient().when(binder.bind(anyString(), eq(AgentShellStarterProperties.class))).thenReturn(bindResult);
            lenient().when(bindResult.isBound()).thenReturn(false);
            lenient().when(bindResult.orElse(any())).thenReturn(defaultProperties);
            lenient().when(bindResult.orElseGet(any())).thenReturn(defaultProperties);

            // When
            processor.postProcessEnvironment(environment, application);

            // Then
            ArgumentCaptor<MapPropertySource> captor = ArgumentCaptor.forClass(MapPropertySource.class);
            verify(propertySources).addFirst(captor.capture());

            MapPropertySource captured = captor.getValue();
            assertEquals("shellModeProperties", captured.getName());

            // Verify default values are set
            assertNotNull(captured.getProperty("spring.main.web-application-type"));
            assertNotNull(captured.getProperty("spring.shell.command.exit.enabled"));
            assertNotNull(captured.getProperty("spring.shell.command.quit.enabled"));
            assertNotNull(captured.getProperty("spring.shell.interactive.enabled"));
            assertNotNull(captured.getProperty("spring.shell.history.enabled"));
        }
    }

    @Test
    void shouldHandleNullWebApplicationType() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(TestClassWithAnnotation.class);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        AgentShellStarterProperties properties = new AgentShellStarterProperties();
        properties.setWebApplicationType(null);

        try (MockedStatic<Binder> binderMock = mockStatic(Binder.class)) {
            Binder binder = mock(Binder.class);
            BindResult<AgentShellStarterProperties> bindResult = mock(BindResult.class);

            binderMock.when(() -> Binder.get(environment)).thenReturn(binder);

            lenient().when(binder.bind(anyString(), any(Bindable.class))).thenReturn(bindResult);
            lenient().when(binder.bind(anyString(), eq(AgentShellStarterProperties.class))).thenReturn(bindResult);
            lenient().when(bindResult.isBound()).thenReturn(true);
            lenient().when(bindResult.get()).thenReturn(properties);
            lenient().when(bindResult.orElse(any())).thenReturn(properties);
            lenient().when(bindResult.orElseGet(any())).thenReturn(properties);

            // When
            processor.postProcessEnvironment(environment, application);

            // Then - should not throw exception
            ArgumentCaptor<MapPropertySource> captor = ArgumentCaptor.forClass(MapPropertySource.class);
            verify(propertySources).addFirst(captor.capture());

            MapPropertySource captured = captor.getValue();
            assertNotNull(captured);
            // Should handle null gracefully by using the null value
            assertNull(captured.getProperty("spring.main.web-application-type"));
        }
    }

    @Test
    void shouldProcessMultipleSourcesWithSomeAnnotated() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(String.class); // Not annotated
        sources.add(TestClassWithAnnotation.class); // Annotated
        sources.add(Integer.class); // Not annotated
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        AgentShellStarterProperties properties = createDefaultProperties();

        try (MockedStatic<Binder> binderMock = mockStatic(Binder.class)) {
            Binder binder = mock(Binder.class);
            BindResult<AgentShellStarterProperties> bindResult = mock(BindResult.class);

            binderMock.when(() -> Binder.get(environment)).thenReturn(binder);

            lenient().when(binder.bind(anyString(), any(Bindable.class))).thenReturn(bindResult);
            lenient().when(binder.bind(anyString(), eq(AgentShellStarterProperties.class))).thenReturn(bindResult);
            lenient().when(bindResult.isBound()).thenReturn(true);
            lenient().when(bindResult.get()).thenReturn(properties);
            lenient().when(bindResult.orElse(any())).thenReturn(properties);
            lenient().when(bindResult.orElseGet(any())).thenReturn(properties);

            // When
            processor.postProcessEnvironment(environment, application);

            // Then - should still process because at least one class is annotated
            verify(propertySources).addFirst(any(MapPropertySource.class));
        }
    }

    @Test
    void shouldHaveCorrectOrder() {
        // Given
        processor = new ShellEnvironmentPostProcessor();

        // Then
        assertEquals(Integer.MIN_VALUE + 10, processor.getOrder());
    }

    @Test
    void shouldProcessEnvironmentWithMultipleAnnotatedClasses() {
        // Given
        processor = new ShellEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(TestClassWithAnnotation.class);
        sources.add(AnotherTestClassWithAnnotation.class);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        AgentShellStarterProperties properties = createDefaultProperties();

        try (MockedStatic<Binder> binderMock = mockStatic(Binder.class)) {
            Binder binder = mock(Binder.class);
            BindResult<AgentShellStarterProperties> bindResult = mock(BindResult.class);

            binderMock.when(() -> Binder.get(environment)).thenReturn(binder);

            lenient().when(binder.bind(anyString(), any(Bindable.class))).thenReturn(bindResult);
            lenient().when(binder.bind(anyString(), eq(AgentShellStarterProperties.class))).thenReturn(bindResult);
            lenient().when(bindResult.isBound()).thenReturn(true);
            lenient().when(bindResult.get()).thenReturn(properties);
            lenient().when(bindResult.orElse(any())).thenReturn(properties);
            lenient().when(bindResult.orElseGet(any())).thenReturn(properties);

            // When
            processor.postProcessEnvironment(environment, application);

            // Then
            verify(propertySources).addFirst(any(MapPropertySource.class));
        }
    }

    private AgentShellStarterProperties createDefaultProperties() {
        AgentShellStarterProperties properties = new AgentShellStarterProperties();
        properties.setWebApplicationType("none");
        properties.getCommand().setExitEnabled(false);
        properties.getCommand().setQuitEnabled(false);
        properties.getInteractive().setEnabled(true);
        properties.getInteractive().setHistoryEnabled(true);
        return properties;
    }

    // Test classes with the required annotation
    @EnableAgentShell
    static class TestClassWithAnnotation {
    }

    @EnableAgentShell
    static class AnotherTestClassWithAnnotation {
    }
}