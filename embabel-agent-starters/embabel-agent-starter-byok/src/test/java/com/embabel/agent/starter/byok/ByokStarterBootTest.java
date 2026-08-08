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
package com.embabel.agent.starter.byok;

import com.embabel.agent.autoconfigure.models.byok.AgentByokAutoConfiguration;
import com.embabel.agent.config.models.byok.SetupRequiredLlm;
import com.embabel.agent.spi.LlmService;
import com.embabel.common.ai.model.ConfigurableModelProvider;
import com.embabel.common.ai.model.ConfigurableModelProviderProperties;
import com.embabel.common.ai.model.DefaultModelSelectionCriteria;
import com.embabel.common.ai.model.EmbeddingService;
import com.embabel.common.ai.model.ModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The starter's reason to exist: an application that depends only on it can start with no
 * provider key configured at all, and the platform can still resolve a default LLM.
 * <p>
 * This runs against the starter's own dependency set, so it also guards the packaging — if a
 * provider autoconfiguration ever leaks onto the classpath it would demand a key and fail here.
 */
class ByokStarterBootTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentByokAutoConfiguration.class))
            .withPropertyValues("embabel.models.default-llm=" + SetupRequiredLlm.NAME);

    @Test
    void startsWithNoProviderKeyConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean(SetupRequiredLlm.NAME);
        });
    }

    @Test
    void theModelProviderResolvesTheDefaultLlmWithoutAnyKey() {
        contextRunner
                .withUserConfiguration(TestableModelProviderConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var modelProvider = context.getBean(ModelProvider.class);
                    LlmService<?> llm = modelProvider.getLlm(DefaultModelSelectionCriteria.INSTANCE);
                    assertThat(llm.getName()).isEqualTo(SetupRequiredLlm.NAME);
                });
    }

    /**
     * Builds the model provider the same way the platform autoconfiguration does — from the
     * {@code LlmService} beans present in the context — so the assertion above exercises the real
     * resolution path rather than a hand-built stand-in.
     */
    @Configuration(proxyBeanMethods = false)
    static class TestableModelProviderConfiguration {

        @Bean
        ModelProvider modelProvider(ApplicationContext applicationContext,
                                    @Value("${embabel.models.default-llm}") String defaultLlm) {
            var properties = new ConfigurableModelProviderProperties();
            properties.setDefaultLlm(defaultLlm);
            // LlmService is F-bounded (LlmService<THIS : LlmService<THIS>>), so a wildcard capture
            // from a stream will not fit the constructor. Collect through the raw type instead.
            List<LlmService<?>> llms = new ArrayList<>();
            for (LlmService<?> llm : applicationContext.getBeansOfType(LlmService.class).values()) {
                llms.add(llm);
            }
            return new ConfigurableModelProvider(llms, List.<EmbeddingService>of(), properties);
        }
    }
}
