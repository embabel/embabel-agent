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
package com.embabel.agent.observability.observation;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link NonEmbabelTracingObservationHandler}.
 * Validates that the handler correctly filters out EmbabelObservationContext
 * while accepting standard Observation.Context instances.
 */
@ExtendWith(MockitoExtension.class)
class NonEmbabelTracingObservationHandlerTest {

    @Mock
    private Tracer tracer;

    private NonEmbabelTracingObservationHandler handler;

    @BeforeEach
    void setUp() {
        handler = new NonEmbabelTracingObservationHandler(tracer);
    }

    @Test
    @DisplayName("Should support standard Observation.Context")
    void supportsStandardContext() {
        // Arrange
        Observation.Context standardContext = new Observation.Context();

        // Act
        boolean result = handler.supportsContext(standardContext);

        // Assert
        assertTrue(result, "Handler should support standard Observation.Context");
    }

    @Test
    @DisplayName("Should NOT support EmbabelObservationContext")
    void doesNotSupportEmbabelContext() {
        // Arrange
        EmbabelObservationContext embabelContext = mock(EmbabelObservationContext.class);

        // Act
        boolean result = handler.supportsContext(embabelContext);

        // Assert
        assertFalse(result, "Handler should NOT support EmbabelObservationContext");
    }

    @Test
    @DisplayName("Should support custom context that extends Observation.Context")
    void supportsCustomNonEmbabelContext() {
        // Arrange
        CustomObservationContext customContext = new CustomObservationContext();

        // Act
        boolean result = handler.supportsContext(customContext);

        // Assert
        assertTrue(result, "Handler should support custom non-Embabel contexts");
    }

    @Test
    @DisplayName("Should accept null context (null instanceof returns false)")
    void acceptsNullContext() {
        // Act
        boolean result = handler.supportsContext(null);

        // Assert
        assertTrue(result, "Handler accepts null context because !(null instanceof EmbabelObservationContext) = true");
    }

    /**
     * Custom context for testing - not an EmbabelObservationContext.
     */
    private static class CustomObservationContext extends Observation.Context {
    }
}
