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
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory;
import com.embabel.common.ai.classification.ClassificationService;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.model.NoSuitableModelException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmDecisionServicesAutoConfigurationTest {

    private final DecisionService triage = mock(DecisionService.class);

    private final DecisionService review = mock(DecisionService.class);

    private final LlmDecisionServiceFactory factory = mock(LlmDecisionServiceFactory.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmDecisionServicesAutoConfiguration.class))
            .withBean(LlmDecisionServiceFactory.class, () -> factory);

    LlmDecisionServicesAutoConfigurationTest() {
        when(factory.decisionService("small-model")).thenReturn(triage);
        when(factory.decisionService("large-model")).thenReturn(review);
        when(factory.decisionService("missing")).thenThrow(NoSuitableModelException.class);
    }

    @Test
    void eachEntryRegistersAServiceNamedAfterItsKey() {
        runner.withPropertyValues(
                        "embabel.agent.platform.decisions.llm.services.triage.llm=small-model",
                        "embabel.agent.platform.decisions.llm.services.review.llm=large-model")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("triage", DecisionService.class)).isSameAs(triage);
                    assertThat(context.getBean("review", DecisionService.class)).isSameAs(review);
                });
    }

    @Test
    void definitionsCarryTheServiceTypeAndAreEager() {
        runner.withPropertyValues("embabel.agent.platform.decisions.llm.services.triage.llm=small-model")
                .run(context -> {
                    var definition = context.getBeanFactory().getBeanDefinition("triage");
                    assertThat(definition.getBeanClassName()).isEqualTo(DecisionService.class.getName());
                    assertThat(definition.isLazyInit()).isFalse();
                });
    }

    @Test
    void aConstructorCanInjectAServiceByQualifier() {
        runner.withBean(TriageConsumer.class)
                .withPropertyValues("embabel.agent.platform.decisions.llm.services.triage.llm=small-model")
                .run(context -> assertThat(context.getBean(TriageConsumer.class).triage).isSameAs(triage));
    }

    @Test
    void noServicesAreRegisteredWhenNoneAreDeclared() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DecisionService.class)).isEmpty();
        });
    }

    @Test
    void retrySettingsBesideTheServicesAreNotUnknownKeys() {
        runner.withPropertyValues(
                        "embabel.agent.platform.decisions.llm.max-attempts=3",
                        "embabel.agent.platform.decisions.llm.services.triage.llm=small-model")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void anUnknownLlmFailsStartupAndNamesTheProperty() {
        runner.withPropertyValues("embabel.agent.platform.decisions.llm.services.triage.llm=missing")
                .run(context -> assertThat(illegalState(context).getMessage())
                        .isEqualTo("embabel.agent.platform.decisions.llm.services.triage.llm names unknown LLM 'missing'"));
    }

    @Test
    void anUnknownLlmFailsStartupEvenWhenBeansAreLazyByDefault() {
        runner.withBean(LazyInitializationBeanFactoryPostProcessor.class)
                .withPropertyValues("embabel.agent.platform.decisions.llm.services.triage.llm=missing")
                .run(context -> assertThat(illegalState(context).getMessage())
                        .isEqualTo("embabel.agent.platform.decisions.llm.services.triage.llm names unknown LLM 'missing'"));
    }

    @Test
    void anEntryWithoutAnLlmFailsStartupAndNamesTheProperty() {
        runner.withPropertyValues("embabel.agent.platform.decisions.llm.services.triage.llm=")
                .run(context -> assertThat(illegalState(context).getMessage())
                        .isEqualTo("embabel.agent.platform.decisions.llm.services.triage.llm must name an LLM"));
    }

    @Test
    void anUnknownKeyUnderAnEntryFailsStartup() {
        runner.withPropertyValues(
                        "embabel.agent.platform.decisions.llm.services.triage.llm=small-model",
                        "embabel.agent.platform.decisions.llm.services.triage.role=fast")
                .run(context -> {
                    var unbound = failures(context).stream()
                            .filter(UnboundConfigurationPropertiesException.class::isInstance)
                            .map(UnboundConfigurationPropertiesException.class::cast)
                            .findFirst()
                            .orElseThrow();
                    assertThat(unbound.getUnboundProperties())
                            .extracting(property -> property.getName().toString())
                            .containsExactly("embabel.agent.platform.decisions.llm.services.triage.role");
                });
    }

    @Test
    void entriesBindFromEnvironmentVariablesWithoutCheckingTheirKeys() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().replace(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of(
                                        "EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_LLM", "small-model",
                                        "EMBABEL_AGENT_PLATFORM_DECISIONS_LLM_SERVICES_TRIAGE_ROLE", "fast"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("triage", DecisionService.class)).isSameAs(triage);
                });
    }

    @Nested
    class ApplicationBeans {

        private final String[] reviewAndTriage = {
                "embabel.agent.platform.decisions.llm.services.llm-review.llm=missing",
                "embabel.agent.platform.decisions.llm.services.triage.llm=small-model",
        };

        @Test
        void anApplicationBeanWithAConfiguredKeyReplacesThatDefinition() {
            runner.withUserConfiguration(ApplicationReviewService.class)
                    .withPropertyValues(reviewAndTriage)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean("llm-review")).isSameAs(ApplicationReviewService.REVIEW);
                    });
        }

        @Test
        void aConfiguredKeyWithoutAnApplicationBeanIsStillBuilt() {
            runner.withUserConfiguration(ApplicationReviewService.class)
                    .withPropertyValues(reviewAndTriage)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean("triage", DecisionService.class)).isSameAs(triage);
                    });
        }

        @Test
        void aSkippedDefinitionIsLoggedOnceAtInfo() {
            var logger = (Logger) LoggerFactory.getLogger(LlmDecisionServicesRegistrar.class);
            var appender = new ListAppender<ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            try {
                runner.withUserConfiguration(ApplicationReviewService.class)
                        .withPropertyValues(reviewAndTriage)
                        .run(context -> assertThat(context).hasNotFailed());
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.INFO)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            "Decision service 'llm-review' is defined by the application; its configured definition is skipped");
        }

        @Test
        void aKeyAlsoConfiguredAsATypeSafeServiceFailsStartupNamingBothProperties() {
            var typeSafe = "embabel.agent.platform.models.typesafe.services.llm-review";
            runner.withInitializer(context -> {
                        var definition = BeanDefinitionBuilder
                                .genericBeanDefinition(DecisionService.class, () -> mock(DecisionService.class))
                                .getBeanDefinition();
                        definition.setAttribute(LlmDecisionServicesRegistrar.CONFIGURED_SERVICE_ATTRIBUTE, typeSafe);
                        ((BeanDefinitionRegistry) context).registerBeanDefinition("llm-review", definition);
                    })
                    .withPropertyValues(reviewAndTriage)
                    .run(context -> assertThat(illegalState(context).getMessage())
                            .contains("embabel.agent.platform.decisions.llm.services.llm-review", typeSafe, "Rename one of the keys"));
        }

        @Test
        void aKeyNamingABeanThatIsNotAServiceFailsStartupNamingTheBean() {
            runner.withBean("llm-review", StringBuilder.class, StringBuilder::new)
                    .withPropertyValues(reviewAndTriage)
                    .run(context -> assertThat(illegalState(context).getMessage())
                            .contains(
                                    "embabel.agent.platform.decisions.llm.services.llm-review",
                                    "'llm-review' of type java.lang.StringBuilder",
                                    "Rename the key"));
        }

        @Test
        void aKeyNamingAnApplicationBeanMethodReturningAServiceIsSkippedWithoutCreatingIt() {
            LazyReviewService.created = false;
            runner.withUserConfiguration(LazyReviewService.class)
                    .withPropertyValues(reviewAndTriage)
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(LazyReviewService.created).isFalse();
                        assertThat(context.getBean("triage", DecisionService.class)).isSameAs(triage);
                    });
        }

        @Test
        void configuredDefinitionsCarryTheirPropertyPath() {
            runner.withPropertyValues("embabel.agent.platform.decisions.llm.services.triage.llm=small-model")
                    .run(context -> assertThat(context.getBeanFactory().getBeanDefinition("triage")
                            .getAttribute(LlmDecisionServicesRegistrar.CONFIGURED_SERVICE_ATTRIBUTE))
                            .isEqualTo("embabel.agent.platform.decisions.llm.services.triage"));
        }
    }

    /** Supplies an application decision service under a key that is also configured. */
    static class ApplicationReviewService {

        static final DecisionService REVIEW = mock(DecisionService.class);

        @Bean("llm-review")
        DecisionService review() {
            return REVIEW;
        }
    }

    /** Declares a lazy classification service under a configured key and records its creation. */
    static class LazyReviewService {

        static volatile boolean created;

        @Bean("llm-review")
        @Lazy
        ClassificationService review() {
            created = true;
            return mock(ClassificationService.class);
        }
    }

    private static List<Throwable> failures(AssertableApplicationContext context) {
        var chain = new ArrayList<Throwable>();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    private static IllegalStateException illegalState(AssertableApplicationContext context) {
        return failures(context).stream()
                .filter(t -> t.getClass() == IllegalStateException.class)
                .map(IllegalStateException.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    static class TriageConsumer {

        final DecisionService triage;

        TriageConsumer(@Qualifier("triage") DecisionService triage) {
            this.triage = triage;
        }
    }
}
