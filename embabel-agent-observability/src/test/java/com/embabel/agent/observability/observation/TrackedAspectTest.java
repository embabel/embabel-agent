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

import com.embabel.agent.observability.annotation.TrackType;
import com.embabel.agent.observability.annotation.Tracked;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TrackedAspect}.
 */
@ExtendWith(MockitoExtension.class)
class TrackedAspectTest {

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    private ObservationRegistry registry;
    private TrackedAspect aspect;
    private CapturingHandler capturingHandler;

    @BeforeEach
    void setUp() {
        registry = ObservationRegistry.create();
        capturingHandler = new CapturingHandler();
        registry.observationConfig().observationHandler(capturingHandler);
        aspect = new TrackedAspect(registry, 4000);
    }

    private void setupJoinPoint(String methodName) throws NoSuchMethodException {
        Method method = SampleService.class.getMethod(methodName, String.class);
        lenient().when(joinPoint.getSignature()).thenReturn(methodSignature);
        lenient().when(methodSignature.getMethod()).thenReturn(method);
        lenient().when(methodSignature.getDeclaringType()).thenReturn(SampleService.class);
        lenient().when(methodSignature.getParameterNames()).thenReturn(new String[]{"input"});
        lenient().when(joinPoint.getArgs()).thenReturn(new Object[]{"test-input"});
    }

    @Nested
    @DisplayName("Observation creation")
    class ObservationCreation {

        @Test
        @DisplayName("Should create observation with custom name from annotation value")
        void shouldUseAnnotationValue() throws Throwable {
            setupJoinPoint("customNamed");
            when(joinPoint.proceed()).thenReturn("result");

            Tracked tracked = SampleService.class.getMethod("customNamed", String.class)
                    .getAnnotation(Tracked.class);

            Object result = aspect.tracked(joinPoint, tracked);

            assertThat(result).isEqualTo("result");
            assertThat(capturingHandler.contexts).hasSize(1);
            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            assertThat(ctx.getName()).isEqualTo("myOperation");
            assertThat(ctx.getEventType()).isEqualTo(EmbabelObservationContext.EventType.CUSTOM);
        }

        @Test
        @DisplayName("Should fallback to method name when annotation value is empty")
        void shouldFallbackToMethodName() throws Throwable {
            setupJoinPoint("defaultNamed");
            when(joinPoint.proceed()).thenReturn("result");

            Tracked tracked = SampleService.class.getMethod("defaultNamed", String.class)
                    .getAnnotation(Tracked.class);

            aspect.tracked(joinPoint, tracked);

            assertThat(capturingHandler.contexts).hasSize(1);
            assertThat(capturingHandler.contexts.get(0).getName()).isEqualTo("defaultNamed");
        }
    }

    @Nested
    @DisplayName("Context enrichment")
    class ContextEnrichment {

        @Test
        @DisplayName("Should tag track type on observation")
        void shouldTagTrackType() throws Throwable {
            setupJoinPoint("processingMethod");
            when(joinPoint.proceed()).thenReturn("ok");

            Tracked tracked = SampleService.class.getMethod("processingMethod", String.class)
                    .getAnnotation(Tracked.class);

            aspect.tracked(joinPoint, tracked);

            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            assertThat(hasLowCardinalityKeyValue(ctx, "embabel.tracked.type", "PROCESSING")).isTrue();
        }

        @Test
        @DisplayName("Should tag description when present")
        void shouldTagDescription() throws Throwable {
            setupJoinPoint("withDescription");
            when(joinPoint.proceed()).thenReturn("ok");

            Tracked tracked = SampleService.class.getMethod("withDescription", String.class)
                    .getAnnotation(Tracked.class);

            aspect.tracked(joinPoint, tracked);

            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            assertThat(hasLowCardinalityKeyValue(ctx, "embabel.tracked.description", "A test description")).isTrue();
        }

        @Test
        @DisplayName("Should not tag description when empty")
        void shouldNotTagEmptyDescription() throws Throwable {
            setupJoinPoint("customNamed");
            when(joinPoint.proceed()).thenReturn("ok");

            Tracked tracked = SampleService.class.getMethod("customNamed", String.class)
                    .getAnnotation(Tracked.class);

            aspect.tracked(joinPoint, tracked);

            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            assertThat(hasLowCardinalityKey(ctx, "embabel.tracked.description")).isFalse();
        }

        @Test
        @DisplayName("Should capture method arguments with parameter names")
        void shouldCaptureArgs() throws Throwable {
            setupJoinPoint("customNamed");
            when(joinPoint.proceed()).thenReturn("result");

            Tracked tracked = SampleService.class.getMethod("customNamed", String.class)
                    .getAnnotation(Tracked.class);

            aspect.tracked(joinPoint, tracked);

            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            assertThat(hasHighCardinalityKeyValue(ctx, "embabel.tracked.args", "{input=test-input}")).isTrue();
        }

        @Test
        @DisplayName("Should capture return value as high cardinality key value")
        void shouldCaptureReturnValue() throws Throwable {
            setupJoinPoint("customNamed");
            when(joinPoint.proceed()).thenReturn("my-result");

            Tracked tracked = SampleService.class.getMethod("customNamed", String.class)
                    .getAnnotation(Tracked.class);

            aspect.tracked(joinPoint, tracked);

            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            assertThat(hasHighCardinalityKeyValue(ctx, "embabel.tracked.result", "my-result")).isTrue();
        }

        @Test
        @DisplayName("Should truncate long arguments using configured maxAttributeLength")
        void shouldTruncateLongArgs() throws Throwable {
            int maxLen = 100;
            TrackedAspect shortAspect = new TrackedAspect(registry, maxLen);

            Method method = SampleService.class.getMethod("customNamed", String.class);
            lenient().when(joinPoint.getSignature()).thenReturn(methodSignature);
            lenient().when(methodSignature.getMethod()).thenReturn(method);
            lenient().when(methodSignature.getDeclaringType()).thenReturn(SampleService.class);
            lenient().when(methodSignature.getParameterNames()).thenReturn(new String[]{"input"});
            String longArg = "x".repeat(300);
            when(joinPoint.getArgs()).thenReturn(new Object[]{longArg});
            when(joinPoint.proceed()).thenReturn("ok");

            Tracked tracked = method.getAnnotation(Tracked.class);

            shortAspect.tracked(joinPoint, tracked);

            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            String argsValue = getHighCardinalityValue(ctx, "embabel.tracked.args");
            assertThat(argsValue).isNotNull();
            // ObservationUtils.truncate keeps first maxLen chars then appends "..."
            assertThat(argsValue.length()).isLessThanOrEqualTo(maxLen + 3);
            assertThat(argsValue).endsWith("...");
        }

        @Test
        @DisplayName("Should handle null result without NPE (null-safe truncate)")
        void shouldHandleNullInTruncate() throws Throwable {
            // TrackedAspect.truncate must be null-safe (delegating to ObservationUtils)
            // Verify via reflection that truncate("null-arg") works and null doesn't cause issues
            setupJoinPoint("customNamed");
            // Pass null as arg element — formatArgs produces "param=null" string (no NPE)
            lenient().when(joinPoint.getArgs()).thenReturn(new Object[]{null});
            when(joinPoint.proceed()).thenReturn(null);

            Tracked tracked = SampleService.class.getMethod("customNamed", String.class)
                    .getAnnotation(Tracked.class);

            // Should not throw NPE
            aspect.tracked(joinPoint, tracked);

            assertThat(capturingHandler.contexts).hasSize(1);
        }

        @Test
        @DisplayName("Should capture multiple parameters with names")
        void shouldCaptureMultipleParamsWithNames() throws Throwable {
            Method method = SampleService.class.getMethod("multiParam", String.class, int.class);
            lenient().when(joinPoint.getSignature()).thenReturn(methodSignature);
            lenient().when(methodSignature.getMethod()).thenReturn(method);
            lenient().when(methodSignature.getDeclaringType()).thenReturn(SampleService.class);
            lenient().when(methodSignature.getParameterNames()).thenReturn(new String[]{"query", "limit"});
            when(joinPoint.getArgs()).thenReturn(new Object[]{"hello", 10});
            when(joinPoint.proceed()).thenReturn("ok");

            Tracked tracked = method.getAnnotation(Tracked.class);

            aspect.tracked(joinPoint, tracked);

            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            assertThat(hasHighCardinalityKeyValue(ctx, "embabel.tracked.args", "{query=hello, limit=10}")).isTrue();
        }

        @Test
        @DisplayName("Should fallback to array format when parameter names are null")
        void shouldFallbackWhenNoParamNames() throws Throwable {
            setupJoinPoint("customNamed");
            lenient().when(methodSignature.getParameterNames()).thenReturn(null);
            when(joinPoint.proceed()).thenReturn("ok");

            Tracked tracked = SampleService.class.getMethod("customNamed", String.class)
                    .getAnnotation(Tracked.class);

            aspect.tracked(joinPoint, tracked);

            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            assertThat(hasHighCardinalityKeyValue(ctx, "embabel.tracked.args", "[test-input]")).isTrue();
        }

        @Test
        @DisplayName("Should tag class name on observation")
        void shouldTagClassName() throws Throwable {
            setupJoinPoint("customNamed");
            when(joinPoint.proceed()).thenReturn("result");

            Tracked tracked = SampleService.class.getMethod("customNamed", String.class)
                    .getAnnotation(Tracked.class);

            aspect.tracked(joinPoint, tracked);

            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            assertThat(hasLowCardinalityKeyValue(ctx, "embabel.tracked.class", "SampleService")).isTrue();
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should record error and rethrow on exception")
        void shouldRecordErrorAndRethrow() throws Throwable {
            setupJoinPoint("customNamed");
            RuntimeException error = new RuntimeException("boom");
            when(joinPoint.proceed()).thenThrow(error);

            Tracked tracked = SampleService.class.getMethod("customNamed", String.class)
                    .getAnnotation(Tracked.class);

            assertThatThrownBy(() -> aspect.tracked(joinPoint, tracked))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("boom");

            assertThat(capturingHandler.contexts).hasSize(1);
            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            assertThat(ctx.getError()).isEqualTo(error);
            assertThat(capturingHandler.stopped).isTrue();
        }
    }

    @Nested
    @DisplayName("Null return value")
    class NullReturnValue {

        @Test
        @DisplayName("Should handle null return value gracefully")
        void shouldHandleNullReturn() throws Throwable {
            setupJoinPoint("customNamed");
            when(joinPoint.proceed()).thenReturn(null);

            Tracked tracked = SampleService.class.getMethod("customNamed", String.class)
                    .getAnnotation(Tracked.class);

            Object result = aspect.tracked(joinPoint, tracked);

            assertThat(result).isNull();
            assertThat(capturingHandler.stopped).isTrue();
            EmbabelObservationContext ctx = capturingHandler.contexts.get(0);
            assertThat(hasHighCardinalityKey(ctx, "embabel.tracked.result")).isFalse();
        }
    }

    // Helper methods for key-value assertions

    private boolean hasLowCardinalityKeyValue(Observation.Context ctx, String key, String value) {
        return ctx.getLowCardinalityKeyValues().stream()
                .anyMatch(kv -> kv.getKey().equals(key) && kv.getValue().equals(value));
    }

    private boolean hasLowCardinalityKey(Observation.Context ctx, String key) {
        return ctx.getLowCardinalityKeyValues().stream()
                .anyMatch(kv -> kv.getKey().equals(key));
    }

    private boolean hasHighCardinalityKeyValue(Observation.Context ctx, String key, String value) {
        return ctx.getHighCardinalityKeyValues().stream()
                .anyMatch(kv -> kv.getKey().equals(key) && kv.getValue().equals(value));
    }

    private boolean hasHighCardinalityKey(Observation.Context ctx, String key) {
        return ctx.getHighCardinalityKeyValues().stream()
                .anyMatch(kv -> kv.getKey().equals(key));
    }

    private String getHighCardinalityValue(Observation.Context ctx, String key) {
        return ctx.getHighCardinalityKeyValues().stream()
                .filter(kv -> kv.getKey().equals(key))
                .map(kv -> kv.getValue())
                .findFirst().orElse(null);
    }

    /**
     * Test handler that captures EmbabelObservationContext for assertions.
     */
    static class CapturingHandler implements ObservationHandler<EmbabelObservationContext> {
        final List<EmbabelObservationContext> contexts = new ArrayList<>();
        boolean stopped = false;

        @Override
        public void onStart(EmbabelObservationContext context) {
            contexts.add(context);
        }

        @Override
        public void onStop(EmbabelObservationContext context) {
            stopped = true;
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return context instanceof EmbabelObservationContext;
        }
    }

    /**
     * Sample service with annotated methods for testing.
     */
    public static class SampleService {

        @Tracked("myOperation")
        public String customNamed(String input) {
            return "result";
        }

        @Tracked
        public String defaultNamed(String input) {
            return "result";
        }

        @Tracked(type = TrackType.PROCESSING)
        public String processingMethod(String input) {
            return "ok";
        }

        @Tracked(value = "described", description = "A test description")
        public String withDescription(String input) {
            return "ok";
        }

        @Tracked("multiOp")
        public String multiParam(String query, int limit) {
            return "ok";
        }
    }
}
