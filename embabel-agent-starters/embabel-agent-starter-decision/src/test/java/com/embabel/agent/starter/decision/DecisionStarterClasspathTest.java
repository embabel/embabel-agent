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
package com.embabel.agent.starter.decision;

import com.embabel.agent.decision.CallFailure;
import com.embabel.agent.decision.DecisionModel;
import com.embabel.agent.decision.DecisionOutcome;
import com.embabel.agent.decision.DecisionRequest;
import com.embabel.agent.spi.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionStarterClasspathTest {
    @Test
    void starterIsInertByDefaultAndDiscoversOptInNoneProvider() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(TestApplication.class)
                // Keep the platform entry point active while satisfying its unrelated model inventory.
                .withBean("starterTestLlm", LlmService.class, DecisionStarterClasspathTest::testLlm)
                .withPropertyValues("embabel.models.default-llm=starter-test");
        runner.run(context -> assertThat(context).doesNotHaveBean(DecisionModel.class));
        runner.withPropertyValues("embabel.agent.decision.enabled=true", "embabel.agent.decision.provider=none")
                .run(context -> {
                    assertThat(context).hasSingleBean(DecisionModel.class).hasBean("decisionModel");
                    DecisionRequest.Builder request = DecisionRequest.builder();
                    request.yesNo("q", "Proceed?");
                    DecisionOutcome.Failure result = (DecisionOutcome.Failure) context.getBean(DecisionModel.class)
                            .ask(request.build());
                    assertThat(result.getFailure()).isEqualTo(CallFailure.Disabled);
                });
    }

    private static LlmService<?> testLlm() {
        LlmService<?> service = mock(LlmService.class);
        when(service.getName()).thenReturn("starter-test");
        when(service.getProvider()).thenReturn("test");
        return service;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
