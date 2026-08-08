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
package com.embabel.agent.autoconfigure.models.byok;

import com.embabel.agent.config.models.byok.SetupRequiredLlm;
import com.embabel.agent.spi.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentByokAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentByokAutoConfiguration.class));

    @Test
    void registersThePlaceholderWithNoApiKeyConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean(SetupRequiredLlm.NAME);
            assertThat(context.getBean(SetupRequiredLlm.NAME)).isInstanceOf(LlmService.class);
        });
    }

    @Test
    void placeholderIsTheOnlyLlmServiceContributed() {
        contextRunner.run(context ->
                assertThat(context.getBeansOfType(LlmService.class)).containsOnlyKeys(SetupRequiredLlm.NAME));
    }
}
