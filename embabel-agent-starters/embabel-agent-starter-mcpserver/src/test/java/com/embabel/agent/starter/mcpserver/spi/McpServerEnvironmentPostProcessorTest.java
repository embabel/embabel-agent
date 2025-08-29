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
package com.embabel.agent.starter.mcpserver.spi;

import com.embabel.agent.config.annotation.EnableAgentMcpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class McpServerEnvironmentPostProcessorTest {

    @Mock
    private ConfigurableEnvironment environment;

    @Mock
    private SpringApplication application;

    @Mock
    private MutablePropertySources propertySources;

    private McpServerEnvironmentPostProcessor processor;

    @Test
    void shouldSkipProcessingWhenNoEnableAgentMcpServerAnnotation() {
        // Given
        processor = new McpServerEnvironmentPostProcessor();
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
        processor = new McpServerEnvironmentPostProcessor();
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
        processor = new McpServerEnvironmentPostProcessor();
        when(application.getAllSources()).thenReturn(null);
        when(environment.getPropertySources()).thenReturn(propertySources);

        // When
        processor.postProcessEnvironment(environment, application);

        // Then - should not throw exception and should skip processing
        verify(propertySources, never()).addFirst(any());
    }

    @Test
    void shouldProcessEnvironmentWhenEnableAgentMcpServerAnnotationPresent() {
        // Given
        processor = new McpServerEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(TestClassWithAnnotation.class);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        verify(propertySources).addFirst(any(MapPropertySource.class));
    }

    @Test
    void shouldProcessMultipleSourcesWithSomeAnnotated() {
        // Given
        processor = new McpServerEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(String.class); // Not annotated
        sources.add(TestClassWithAnnotation.class); // Annotated
        sources.add(Integer.class); // Not annotated
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        // When
        processor.postProcessEnvironment(environment, application);

        // Then - should still process because at least one class is annotated
        verify(propertySources).addFirst(any(MapPropertySource.class));
    }

    @Test
    void shouldHaveCorrectOrder() {
        // Given
        processor = new McpServerEnvironmentPostProcessor();

        // Then
        assertEquals(Integer.MIN_VALUE + 10, processor.getOrder());
    }

    @Test
    void shouldProcessEnvironmentWithMultipleAnnotatedClasses() {
        // Given
        processor = new McpServerEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(TestClassWithAnnotation.class);
        sources.add(AnotherTestClassWithAnnotation.class);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        verify(propertySources).addFirst(any(MapPropertySource.class));
    }

    @Test
    void shouldSetCorrectPropertyValues() {
        // Given
        processor = new McpServerEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add(TestClassWithAnnotation.class);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        ArgumentCaptor<MapPropertySource> captor = ArgumentCaptor.forClass(MapPropertySource.class);
        verify(propertySources).addFirst(captor.capture());

        MapPropertySource captured = captor.getValue();
        assertEquals("mcpServerModeProperties", captured.getName());
        assertEquals(true, captured.getProperty("embabel.agent.mcpserver.enabled"));
        assertEquals(1, captured.getSource().size());
    }

    @Test
    void shouldSkipProcessingWithNonClassSources() {
        // Given
        processor = new McpServerEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add("not-a-class"); // String instead of Class
        sources.add(123); // Integer instead of Class
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        // When
        processor.postProcessEnvironment(environment, application);

        // Then
        verify(propertySources, never()).addFirst(any());
    }

    @Test
    void shouldProcessMixedSourceTypes() {
        // Given
        processor = new McpServerEnvironmentPostProcessor();
        Set<Object> sources = new HashSet<>();
        sources.add("string-source");
        sources.add(TestClassWithAnnotation.class); // This one has annotation
        sources.add(42);
        when(application.getAllSources()).thenReturn(sources);
        when(environment.getPropertySources()).thenReturn(propertySources);

        // When
        processor.postProcessEnvironment(environment, application);

        // Then - should process because one valid annotated class exists
        verify(propertySources).addFirst(any(MapPropertySource.class));
    }

    // Test classes with the required annotation
    @EnableAgentMcpServer
    static class TestClassWithAnnotation {
    }

    @EnableAgentMcpServer
    static class AnotherTestClassWithAnnotation {
    }
}
