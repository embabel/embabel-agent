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
package com.embabel.agent.api.template;

import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlatformServices;
import com.embabel.agent.api.common.PromptRunner;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.common.textio.template.NoSuchTemplateException;
import com.embabel.common.textio.template.TemplateRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Java-idiom tests for {@link TemplatedPromptRunnerBuilder}.
 */
@ExtendWith(MockitoExtension.class)
class TemplatedPromptRunnerBuilderJavaTest {

    @Mock
    private OperationContext mockContext;

    @Mock
    private AgentPlatform mockPlatform;

    @Mock
    private PlatformServices mockPlatformServices;

    @Mock
    private TemplateRenderer mockRenderer;

    @Mock
    private PromptRunner mockRunner;

    @Mock
    private PromptRunner mockRunnerWithSystemPrompt;

    @Test
    @DisplayName("Should expose context and runner")
    void exposesContextAndRunner() {
        // Arrange & Act
        var builder = new TemplatedPromptRunnerBuilder(mockContext, mockRunner);

        // Assert
        assertEquals(mockContext, builder.getContext());
        assertEquals(mockRunner, builder.getRunner());
    }

    @Test
    @DisplayName("Should render template via platform renderer and pass result as system prompt")
    void rendersTemplateAndDelegatesToWithSystemPrompt() {
        // Arrange
        var model = Map.<String, Object>of("company", "Acme");
        stubPlatformRenderer();
        when(mockRenderer.renderLoadedTemplate("greeting", model)).thenReturn("You are the Acme assistant");
        when(mockRunner.withSystemPrompt("You are the Acme assistant")).thenReturn(mockRunnerWithSystemPrompt);
        var builder = new TemplatedPromptRunnerBuilder(mockContext, mockRunner);

        // Act
        var result = builder.withSystemPromptTemplate("greeting", model);

        // Assert
        assertEquals(mockRunnerWithSystemPrompt, result);
        verify(mockRunner).withSystemPrompt("You are the Acme assistant");
    }

    @Test
    @DisplayName("Should default to an empty model for static templates")
    void rendersStaticTemplateWithoutModel() {
        // Arrange
        stubPlatformRenderer();
        when(mockRenderer.renderLoadedTemplate("static", Map.of())).thenReturn("You are a helpful assistant");
        when(mockRunner.withSystemPrompt("You are a helpful assistant")).thenReturn(mockRunnerWithSystemPrompt);
        var builder = new TemplatedPromptRunnerBuilder(mockContext, mockRunner);

        // Act
        var result = builder.withSystemPromptTemplate("static");

        // Assert
        assertEquals(mockRunnerWithSystemPrompt, result);
    }

    @Test
    @DisplayName("Should propagate NoSuchTemplateException for an unknown template")
    void propagatesNoSuchTemplateException() {
        // Arrange
        var model = Map.<String, Object>of();
        stubPlatformRenderer();
        when(mockRenderer.renderLoadedTemplate("missing", model))
                .thenThrow(new NoSuchTemplateException("missing"));
        var builder = new TemplatedPromptRunnerBuilder(mockContext, mockRunner);

        // Act & Assert
        assertThrows(NoSuchTemplateException.class,
                () -> builder.withSystemPromptTemplate("missing", model));
    }

    private void stubPlatformRenderer() {
        when(mockContext.agentPlatform()).thenReturn(mockPlatform);
        when(mockPlatform.getPlatformServices()).thenReturn(mockPlatformServices);
        when(mockPlatformServices.getTemplateRenderer()).thenReturn(mockRenderer);
    }
}
