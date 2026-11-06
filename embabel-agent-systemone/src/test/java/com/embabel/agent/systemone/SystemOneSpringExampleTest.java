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
package com.embabel.agent.systemone;

import static org.assertj.core.api.Assertions.assertThat;

import com.embabel.common.ai.decision.DecisionService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

class SystemOneSpringExampleTest {

    @Test
    void localModelsAreOrdinaryDecisionServiceBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(LocalDecisionModels.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBeansOfType(DecisionService.class))
                                    .containsOnlyKeys("clef-flash");
                            var clefFlash = context.getBean("clef-flash", DecisionService.class);
                            assertThat(clefFlash.getName()).isEqualTo("clef-flash");
                            assertThat(clefFlash.getProvider()).isEqualTo("Ollama");
                        });
    }

    // tag::local-spring[]
    @Configuration(proxyBeanMethods = false)
    static class LocalDecisionModels {

        @Bean
        SystemOneModelFactory ollamaDecisionModels() {
            var ollama =
                    new SystemOneEndpoint(
                            "Ollama",
                            URI.create("http://localhost:11434"),
                            Duration.ofSeconds(10),
                            Duration.ofMinutes(2));
            return new SystemOneModelFactory(ollama, "clef-flash", () -> null);
        }

        @Bean("clef-flash")
        DecisionService clefFlash(@Qualifier("ollamaDecisionModels") SystemOneModelFactory models) {
            return models.build("clef-flash");
        }
    }
    // end::local-spring[]
}
