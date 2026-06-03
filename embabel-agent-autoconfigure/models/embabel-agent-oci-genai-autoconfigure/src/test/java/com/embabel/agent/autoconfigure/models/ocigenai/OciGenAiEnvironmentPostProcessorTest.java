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
package com.embabel.agent.autoconfigure.models.ocigenai;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OciGenAiEnvironmentPostProcessorTest {

    @Nested
    class Defaults {

        @Test
        void suppliesOciDefaultsWhenNoUserDefaultsAreConfigured() {
            var environment = new StandardEnvironment();
            var processor = new TestOciGenAiEnvironmentPostProcessor(false);

            processor.postProcessEnvironment(environment, new SpringApplication());

            assertThat(environment.getProperty(OciGenAiEnvironmentPostProcessor.DEFAULT_LLM_PROPERTY))
                    .isEqualTo(OciGenAiEnvironmentPostProcessor.OCI_DEFAULT_LLM);
            assertThat(environment.getProperty(OciGenAiEnvironmentPostProcessor.DEFAULT_EMBEDDING_PROPERTY))
                    .isEqualTo(OciGenAiEnvironmentPostProcessor.OCI_DEFAULT_EMBEDDING);
        }

        @Test
        void ociDefaultsOverrideFrameworkDefaultsAddedLater() {
            var environment = new StandardEnvironment();
            var processor = new TestOciGenAiEnvironmentPostProcessor(false);

            processor.postProcessEnvironment(environment, new SpringApplication());
            environment.getPropertySources().addLast(
                    new MapPropertySource(
                            "frameworkDefaults",
                            Map.of(
                                    OciGenAiEnvironmentPostProcessor.DEFAULT_LLM_PROPERTY, "gpt-4.1-mini",
                                    OciGenAiEnvironmentPostProcessor.DEFAULT_EMBEDDING_PROPERTY, "text-embedding-3-small"
                            )
                    )
            );

            assertThat(environment.getProperty(OciGenAiEnvironmentPostProcessor.DEFAULT_LLM_PROPERTY))
                    .isEqualTo(OciGenAiEnvironmentPostProcessor.OCI_DEFAULT_LLM);
            assertThat(environment.getProperty(OciGenAiEnvironmentPostProcessor.DEFAULT_EMBEDDING_PROPERTY))
                    .isEqualTo(OciGenAiEnvironmentPostProcessor.OCI_DEFAULT_EMBEDDING);
        }

        @Test
        void preservesUserConfiguredDefaults() {
            var environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(
                    new MapPropertySource(
                            "testDefaults",
                            Map.of(
                                    OciGenAiEnvironmentPostProcessor.DEFAULT_LLM_PROPERTY, "user-llm",
                                    OciGenAiEnvironmentPostProcessor.DEFAULT_EMBEDDING_PROPERTY, "user-embedding"
                            )
                    )
            );
            var processor = new TestOciGenAiEnvironmentPostProcessor(false);

            processor.postProcessEnvironment(environment, new SpringApplication());

            assertThat(environment.getProperty(OciGenAiEnvironmentPostProcessor.DEFAULT_LLM_PROPERTY))
                    .isEqualTo("user-llm");
            assertThat(environment.getProperty(OciGenAiEnvironmentPostProcessor.DEFAULT_EMBEDDING_PROPERTY))
                    .isEqualTo("user-embedding");
        }
    }

    @Nested
    class OpenAiProvider {

        @Test
        void doesNotOverrideOpenAiDefaultWhenOpenAiProviderIsPresent() {
            var environment = new StandardEnvironment();
            var processor = new TestOciGenAiEnvironmentPostProcessor(true);

            processor.postProcessEnvironment(environment, new SpringApplication());

            assertThat(environment.getProperty(OciGenAiEnvironmentPostProcessor.DEFAULT_LLM_PROPERTY)).isNull();
            assertThat(environment.getProperty(OciGenAiEnvironmentPostProcessor.DEFAULT_EMBEDDING_PROPERTY)).isNull();
        }
    }

    @Nested
    class Ordering {

        @Test
        void runsAfterBootConfigDataEnvironmentPostProcessor() {
            var processor = new OciGenAiEnvironmentPostProcessor();

            assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
        }
    }

    private static final class TestOciGenAiEnvironmentPostProcessor extends OciGenAiEnvironmentPostProcessor {

        private final boolean openAiProviderPresent;

        private TestOciGenAiEnvironmentPostProcessor(boolean openAiProviderPresent) {
            this.openAiProviderPresent = openAiProviderPresent;
        }

        @Override
        boolean isOpenAiProviderPresent() {
            return openAiProviderPresent;
        }
    }
}
