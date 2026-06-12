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
package com.embabel.agent.autoconfigure.observability;

import com.embabel.agent.spi.config.spring.EmbeddingTrackingConfiguration;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.PricingModel;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring test for standalone (non-agent) embedding tracing.
 *
 * <p>Verifies that {@link com.embabel.agent.observability.observation.EmbabelSpanEventListener},
 * registered by {@link ObservabilityAutoConfiguration}, is picked up as an
 * {@link com.embabel.agent.api.event.EmbeddingEventListener} bean and folded by
 * {@link EmbeddingTrackingConfiguration}'s bean post-processor into the {@code EmbeddingOperations}
 * decorator wrapping every {@link EmbeddingService}. The effect: an embedding call made <em>outside</em>
 * an agent process (RAG / pgvector vector search) produces an {@code embabel.embedding} span, which the
 * agent-only {@code onProcessEvent} channel never delivered.
 */
class StandaloneEmbeddingTracingTest {

    /** Captures stopped observations so the test can assert which spans were produced. */
    private static final class RecordingHandler implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
            .withUserConfiguration(EmbeddingTrackingConfiguration.class, TestEmbeddingConfig.class);

    @Test
    void standaloneEmbed_producesEmbeddingSpan() {
        contextRunner.run(context -> {
            RecordingHandler handler = context.getBean(RecordingHandler.class);
            EmbeddingService service = context.getBean(EmbeddingService.class);

            // No AgentProcess on this thread => standalone path.
            service.embed("hello world");

            assertThat(handler.stopped)
                    .as("standalone embed must produce an embabel.embedding span via the EmbeddingEventListener channel")
                    .anyMatch(c -> "embabel.embedding".equals(c.getName()));
        });
    }

    @Test
    void standaloneEmbed_producesNoSpan_whenTracingDisabled() {
        contextRunner
                .withPropertyValues("embabel.observability.tracing-enabled=false")
                .run(context -> {
                    RecordingHandler handler = context.getBean(RecordingHandler.class);
                    EmbeddingService service = context.getBean(EmbeddingService.class);

                    service.embed("hello world");

                    assertThat(handler.stopped)
                            .as("no embedding span when the span listener bean is not created")
                            .noneMatch(c -> "embabel.embedding".equals(c.getName()));
                });
    }

    @Configuration
    static class TestEmbeddingConfig {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }

        @Bean
        ObservationRegistry observationRegistry(RecordingHandler handler) {
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(handler);
            return registry;
        }

        @Bean
        EmbeddingService embeddingService() {
            return new StubEmbeddingService();
        }
    }

    /** Minimal in-memory embedding service: returns a fixed vector, declares pricing so cost is exercised. */
    static class StubEmbeddingService implements EmbeddingService {

        @NotNull
        @Override
        public float[] embed(@NotNull String text) {
            return new float[]{0.1f, 0.2f, 0.3f};
        }

        @NotNull
        @Override
        public List<float[]> embed(@NotNull List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        @Override
        public int getDimensions() {
            return 3;
        }

        @NotNull
        @Override
        public String getName() {
            return "stub-embedding";
        }

        @NotNull
        @Override
        public String getProvider() {
            return "test";
        }

        @Override
        public PricingModel getPricingModel() {
            return PricingModel.usdPer1MTokens(0.02, 0.0);
        }
    }
}
