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
package com.embabel.agent.autoconfigure.platform;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.embabel.agent.core.internal.LlmOperations;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.PlaceholderLlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.support.NoOpDecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import com.embabel.common.ai.model.DefaultModelSelectionCriteria;
import com.embabel.common.ai.model.ModelProvider;
import com.embabel.common.ai.model.ModelSelectionCriteria;
import com.embabel.common.ai.model.ServiceSelectionException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** With no decision service of any kind registered, the platform offers a prompted one over the default LLM. */
class LlmDecisionDefaultCandidateAutoConfigurationTest {

    private static final String FALLBACK = LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE;

    private final LlmService<?> llm = new SpringAiLlmService("gpt-test", "TestProvider", mock(ChatModel.class));

    private final LlmOperations llmOperations = mock(LlmOperations.class);

    private final ModelProvider modelProvider = mock(ModelProvider.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LlmDecisionServicesAutoConfiguration.class,
                    LlmDecisionDefaultCandidateAutoConfiguration.class,
                    DecisionServiceRegistryAutoConfiguration.class))
            .withBean(LlmOperations.class, () -> llmOperations)
            .withBean(ModelProvider.class, () -> modelProvider);

    LlmDecisionDefaultCandidateAutoConfigurationTest() {
        when(modelProvider.getLlm(DefaultModelSelectionCriteria.INSTANCE)).thenAnswer(call -> llm);
        when(modelProvider.getLlm(ModelSelectionCriteria.byName("gpt-test"))).thenAnswer(call -> llm);
    }

    @Nested
    class EmptyRegistry {

        @Test
        void aPromptedServiceOverTheDefaultLlmIsRegistered() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean(FALLBACK);
                assertThat(context.getBean(FALLBACK, DecisionService.class).getName()).isEqualTo("gpt-test");
            });
        }

        @Test
        void theServiceIsOfferedAsTheDefaultCandidate() {
            runner.run(context -> assertThat(context.getBean(DecisionServiceRegistry.DefaultCandidate.class))
                    .isEqualTo(new DecisionServiceRegistry.DefaultCandidate(FALLBACK)));
        }

        @Test
        void theServiceIsTheDefaultOfBothFamilies() {
            runner.run(context -> {
                var fallback = context.getBean(FALLBACK);
                var registry = context.getBean(DecisionServiceRegistry.class);
                assertThat(registry.decisions().defaultService()).isSameAs(fallback);
                assertThat(registry.classifications().defaultService()).isSameAs(fallback);
            });
        }

        @Test
        void anInfoLineNamesTheModelAndTheOptOutProperty() {
            var lines = capturing(Level.INFO, () -> runner.run(context -> assertThat(context).hasNotFailed()));
            assertThat(lines).containsExactly(
                    "Decision and classification family default is the prompted service 'llmDefaultDecisionService'"
                            + " over LLM 'gpt-test'; set embabel.agent.platform.decisions.llm.default-candidate=false"
                            + " to turn this off");
        }

        @Test
        void aPlaceholderModelIsAcceptedAndLoggedAtWarn() {
            LlmService<?> placeholder = mock(LlmService.class, withSettings().extraInterfaces(PlaceholderLlmService.class));
            when(placeholder.getName()).thenReturn("setup-required");
            when(placeholder.getProvider()).thenReturn("none");
            when(modelProvider.getLlm(DefaultModelSelectionCriteria.INSTANCE)).thenAnswer(call -> placeholder);
            var warnings = capturing(Level.WARN, () -> runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean(FALLBACK);
            }));
            assertThat(warnings).singleElement().asString()
                    .startsWith("Decision and classification family default is backed by a placeholder model");
        }
    }

    @Nested
    class StandingDown {

        @Test
        void aConfiguredPromptedServiceKeepsTheSingleServiceRule() {
            runner.withPropertyValues("embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(FALLBACK);
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.CANDIDATE);
                        assertThat(context).doesNotHaveBean(DecisionServiceRegistry.DefaultCandidate.class);
                        assertThat(context.getBean(DecisionServiceRegistry.class).decisions().defaultService())
                                .isSameAs(context.getBean("triage"));
                    });
        }

        @Test
        void anApplicationServiceBeanKeepsTheFallbackAway() {
            runner.withBean("mine", DecisionService.class, () -> new NoOpDecisionService("mine"))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(FALLBACK);
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.CANDIDATE);
                    });
        }

        @Test
        void anotherDefaultCandidateKeepsTheFallbackAway() {
            DecisionService other = new NoOpDecisionService("other");
            runner.withBean("otherCandidate", DecisionServiceRegistry.DefaultCandidate.class,
                            () -> new DecisionServiceRegistry.DefaultCandidate("other"))
                    .withBean("other", DecisionService.class, () -> other)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(FALLBACK);
                        assertThat(context.getBean(DecisionServiceRegistry.class).decisions().defaultService())
                                .isSameAs(other);
                    });
        }

        @Test
        void theOptOutPropertyRemovesBothBeansAndLeavesNoDefault() {
            runner.withPropertyValues("embabel.agent.platform.decisions.llm.default-candidate=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(FALLBACK);
                        assertThat(context).doesNotHaveBean(DecisionServiceRegistry.DefaultCandidate.class);
                        var decisions = context.getBean(DecisionServiceRegistry.class).decisions();
                        assertThatThrownBy(decisions::defaultService)
                                .isInstanceOfSatisfying(ServiceSelectionException.class, e ->
                                        assertThat(e.getReason()).isEqualTo(ServiceSelectionException.Reason.NO_DEFAULT));
                    });
        }

        @Test
        void withoutAModelProviderNothingIsRegistered() {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            LlmDecisionServicesAutoConfiguration.class,
                            LlmDecisionDefaultCandidateAutoConfiguration.class,
                            DecisionServiceRegistryAutoConfiguration.class))
                    .withBean(LlmOperations.class, () -> llmOperations)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(FALLBACK);
                        assertThat(context).doesNotHaveBean(DecisionServiceRegistry.DefaultCandidate.class);
                    });
        }
    }

    private static List<String> capturing(Level level, Runnable block) {
        var logger = (Logger) LoggerFactory.getLogger(LlmDecisionDefaultCandidateRegistrar.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            block.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
