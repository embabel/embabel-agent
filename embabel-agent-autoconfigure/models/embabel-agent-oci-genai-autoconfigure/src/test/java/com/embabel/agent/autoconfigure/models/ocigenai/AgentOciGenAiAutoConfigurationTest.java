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

import com.embabel.common.ai.autoconfig.ProviderInitialization;
import com.embabel.common.ai.model.EmbeddingService;
import com.oracle.bmc.generativeaiinference.GenerativeAiInference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentOciGenAiAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentOciGenAiAutoConfiguration.class))
            .withUserConfiguration(FakeOciClientConfiguration.class)
            .withPropertyValues("embabel.agent.platform.models.ocigenai.compartment-id=ocid1.compartment.oc1..test");

    @Test
    void registersProviderInitializationFromBundledMetadata() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ProviderInitialization.class);
            assertThat(context.getBean(ProviderInitialization.class).getProvider()).isEqualTo("OCIGenAI");
            assertThat(context.getBean(ProviderInitialization.class).getRegisteredLlms()).isNotEmpty();
            assertThat(context.getBean(ProviderInitialization.class).getRegisteredEmbeddings()).isNotEmpty();
            assertThat(context).hasBean("llama_4_maverick");
            assertThat(context).hasBean("cohere_embed_v4");
            assertThat(context.getBean("cohere_embed_v4")).isInstanceOf(EmbeddingService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class FakeOciClientConfiguration {
        @Bean
        GenerativeAiInference generativeAiInference() {
            return mock(GenerativeAiInference.class);
        }
    }
}
