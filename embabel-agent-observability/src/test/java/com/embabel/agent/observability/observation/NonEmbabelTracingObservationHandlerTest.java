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
    @DisplayName("Handler should be created with tracer")
    void createsHandlerWithTracer() {
        assertNotNull(handler);
    }

    @Test
    @DisplayName("Should support standard Observation.Context")
    void supportsStandardContext() {
        Observation.Context standardContext = new Observation.Context();

        boolean result = handler.supportsContext(standardContext);

        assertTrue(result, "Handler should support standard Observation.Context");
    }

    @Test
    @DisplayName("Should NOT support EmbabelObservationContext")
    void doesNotSupportEmbabelContext() {
        EmbabelObservationContext embabelContext = mock(EmbabelObservationContext.class);

        boolean result = handler.supportsContext(embabelContext);

        assertFalse(result, "Handler should NOT support EmbabelObservationContext");
    }

    @Test
    @DisplayName("Should support custom context that extends Observation.Context")
    void supportsCustomNonEmbabelContext() {
        CustomObservationContext customContext = new CustomObservationContext();

        boolean result = handler.supportsContext(customContext);

        assertTrue(result, "Handler should support custom non-Embabel contexts");
    }

    @Test
    @DisplayName("Should accept null context (null instanceof returns false)")
    void acceptsNullContext() {
        boolean result = handler.supportsContext(null);

        assertTrue(result, "Handler accepts null context because !(null instanceof EmbabelObservationContext) = true");
    }

    /**
     * Custom context for testing - not an EmbabelObservationContext.
     */
    private static class CustomObservationContext extends Observation.Context {
        CustomObservationContext() {
            super();
        }
    }
}
