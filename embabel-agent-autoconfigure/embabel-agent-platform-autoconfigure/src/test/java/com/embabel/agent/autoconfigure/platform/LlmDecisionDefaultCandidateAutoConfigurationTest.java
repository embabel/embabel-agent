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
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory;
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
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * When the property is true and no decision service of any kind is registered, the platform offers a
 * prompted one over the default LLM.
 */
class LlmDecisionDefaultCandidateAutoConfigurationTest {

    private static final String TURNED_ON = LlmDecisionDefaultCandidateRegistrar.PROPERTY + "=true";

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
    class TurnedOff {

        @Test
        void nothingIsRegisteredUnlessThePropertyIsTrue() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
                assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.CANDIDATE);
                assertNoDefault(context.getBean(DecisionServiceRegistry.class));
            });
        }

        @Test
        void thePropertySetToFalseRemovesBothBeansAndLeavesNoDefault() {
            runner.withPropertyValues(LlmDecisionDefaultCandidateRegistrar.PROPERTY + "=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
                        assertThat(context).doesNotHaveBean(DecisionServiceRegistry.DefaultCandidate.class);
                        assertNoDefault(context.getBean(DecisionServiceRegistry.class));
                    });
        }
    }

    @Nested
    class EmptyRegistry {

        private final ApplicationContextRunner on = runner.withPropertyValues(TURNED_ON);

        @Test
        void aPromptedServiceOverTheDefaultLlmIsRegistered() {
            on.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
                assertThat(context.getBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE, DecisionService.class).getName()).isEqualTo("gpt-test");
            });
        }

        @Test
        void theServiceIsOfferedAsTheDefaultCandidate() {
            on.run(context -> assertThat(context.getBean(DecisionServiceRegistry.DefaultCandidate.class))
                    .isEqualTo(new DecisionServiceRegistry.DefaultCandidate(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE)));
        }

        @Test
        void theServiceIsTheDefaultOfBothFamilies() {
            on.run(context -> {
                var prompted = context.getBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
                var registry = context.getBean(DecisionServiceRegistry.class);
                assertThat(registry.decisions().defaultService()).isSameAs(prompted);
                assertThat(registry.classifications().defaultService()).isSameAs(prompted);
            });
        }

        @Test
        void anInfoLineNamesTheModelAndTheProperty() {
            var lines = capturing(Level.INFO, () -> on.run(context -> assertThat(context).hasNotFailed()));
            assertThat(lines).containsExactly(
                    "Decision and classification family default is the prompted service 'llmDefaultDecisionService'"
                            + " over LLM 'gpt-test', turned on by embabel.agent.platform.decisions.llm.default-candidate=true");
        }

        @Test
        void aPlaceholderModelIsAcceptedAndLoggedAtWarn() {
            LlmService<?> placeholder = mock(LlmService.class, withSettings().extraInterfaces(PlaceholderLlmService.class));
            when(placeholder.getName()).thenReturn("setup-required");
            when(placeholder.getProvider()).thenReturn("none");
            when(modelProvider.getLlm(DefaultModelSelectionCriteria.INSTANCE)).thenAnswer(call -> placeholder);
            var warnings = capturing(Level.WARN, () -> on.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
            }));
            assertThat(warnings).singleElement().asString()
                    .startsWith("Decision and classification family default has no chat model yet");
        }

        @Test
        void aModelConfiguredAfterStartupIsUsedWithoutRestart() {
            LlmService<?> placeholder = mock(LlmService.class, withSettings().extraInterfaces(PlaceholderLlmService.class));
            when(placeholder.getName()).thenReturn("setup-required");
            when(placeholder.getProvider()).thenReturn("none");
            var calls = new AtomicInteger();
            when(modelProvider.getLlm(DefaultModelSelectionCriteria.INSTANCE))
                    .thenAnswer(call -> calls.getAndIncrement() == 0 ? placeholder : llm);
            var warnings = capturing(Level.WARN, () -> on.run(context -> {
                assertThat(context).hasNotFailed();
                var service = context.getBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE, DecisionService.class);
                assertThat(service.getName()).isEqualTo("gpt-test");
                assertThat(service.getProvider()).isEqualTo("TestProvider");
            }));
            assertThat(warnings).singleElement().asString().contains("'setup-required'");
        }
    }

    @Nested
    class StandingDown {

        private final ApplicationContextRunner on = runner.withPropertyValues(TURNED_ON);

        @Test
        void aConfiguredPromptedServiceKeepsTheSingleServiceRule() {
            on.withPropertyValues("embabel.agent.platform.decisions.llm.services.triage.llm=gpt-test")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.CANDIDATE);
                        assertThat(context).doesNotHaveBean(DecisionServiceRegistry.DefaultCandidate.class);
                        assertThat(context.getBean(DecisionServiceRegistry.class).decisions().defaultService())
                                .isSameAs(context.getBean("triage"));
                    });
        }

        @Test
        void anApplicationServiceBeanKeepsTheDefaultAway() {
            on.withBean("mine", DecisionService.class, () -> new NoOpDecisionService("mine"))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.CANDIDATE);
                    });
        }

        @Test
        void anotherDefaultCandidateKeepsTheDefaultAway() {
            DecisionService other = new NoOpDecisionService("other");
            on.withBean("otherCandidate", DecisionServiceRegistry.DefaultCandidate.class,
                            () -> new DecisionServiceRegistry.DefaultCandidate("other"))
                    .withBean("other", DecisionService.class, () -> other)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
                        assertThat(context.getBean(DecisionServiceRegistry.class).decisions().defaultService())
                                .isSameAs(other);
                    });
        }

        @Test
        void anExplicitFamilyDefaultNamingAnApplicationServiceWins() {
            on.withBean("triage", DecisionService.class, () -> new NoOpDecisionService("triage"))
                    .withPropertyValues("embabel.models.decision.default=triage")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
                        assertThat(context.getBean(DecisionServiceRegistry.class).decisions().defaultService())
                                .isSameAs(context.getBean("triage"));
                    });
        }

        @Test
        void aServiceRegisteredByAnApplicationPostProcessorKeepsTheDefaultAway() {
            on.withUserConfiguration(PostProcessorRegisteredService.class)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasBean("registered");
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.CANDIDATE);
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
                    .withPropertyValues(TURNED_ON)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(LlmDecisionDefaultCandidateRegistrar.DEFAULT_SERVICE);
                        assertThat(context).doesNotHaveBean(DecisionServiceRegistry.DefaultCandidate.class);
                    });
        }
    }

    @Nested
    class Failing {

        @Test
        void aSecondModelProviderFailsStartupWithAMessageNamingTheProperty() {
            runner.withPropertyValues(TURNED_ON)
                    .withBean("secondModelProvider", ModelProvider.class, () -> mock(ModelProvider.class))
                    .withBean(LlmDecisionServiceFactory.class, () -> mock(LlmDecisionServiceFactory.class))
                    .run(context -> assertThat(context).getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining(LlmDecisionDefaultCandidateRegistrar.PROPERTY)
                            .hasMessageContaining("ModelProvider bean is missing or ambiguous"));
        }
    }

    /** Registers a decision service from a post-processor, the way a library might. */
    @Configuration(proxyBeanMethods = false)
    static class PostProcessorRegisteredService {

        @Bean
        static BeanDefinitionRegistryPostProcessor registersADecisionService() {
            return (BeanDefinitionRegistry registry) -> registry.registerBeanDefinition(
                    "registered",
                    BeanDefinitionBuilder.genericBeanDefinition(
                                    DecisionService.class, () -> new NoOpDecisionService("registered"))
                            .getBeanDefinition());
        }
    }

    private static void assertNoDefault(DecisionServiceRegistry registry) {
        var decisions = registry.decisions();
        assertThatThrownBy(decisions::defaultService)
                .isInstanceOfSatisfying(ServiceSelectionException.class, e ->
                        assertThat(e.getReason()).isEqualTo(ServiceSelectionException.Reason.NO_DEFAULT));
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
