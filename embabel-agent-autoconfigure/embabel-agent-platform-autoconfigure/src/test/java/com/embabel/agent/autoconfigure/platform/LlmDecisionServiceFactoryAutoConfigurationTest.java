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
import com.embabel.agent.core.support.LlmInteraction;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.common.RetryProperties;
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.model.ModelProvider;
import com.embabel.common.ai.model.ModelSelectionCriteria;
import com.embabel.common.util.EmbabelObjectMapperHolder;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The platform autoconfiguration supplies the factory applications inject, with bound retry settings. */
class LlmDecisionServiceFactoryAutoConfigurationTest {

    private final LlmService<?> llm = new SpringAiLlmService("gpt-test", "TestProvider", mock(ChatModel.class));

    private final LlmOperations llmOperations = mock(LlmOperations.class);

    private final ModelProvider modelProvider = mock(ModelProvider.class);

    private final PropositionRequest proposition =
            new PropositionRequest("My card was charged twice", "The customer wants a refund");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LlmDecisionServicesAutoConfiguration.class))
            .withBean(LlmOperations.class, () -> llmOperations)
            .withBean(ModelProvider.class, () -> modelProvider);

    private final String[] quickRetry = {
            "embabel.agent.platform.decisions.llm.max-attempts=2",
            "embabel.agent.platform.decisions.llm.backoff-millis=1",
            "embabel.agent.platform.decisions.llm.backoff-max-interval=2",
    };

    LlmDecisionServiceFactoryAutoConfigurationTest() {
        when(modelProvider.getLlm(ModelSelectionCriteria.byName("gpt-test"))).thenAnswer(call -> llm);
    }

    /** Stands in for the platform's operations by reading one canned model reply into the answer type asked for. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void modelReplies(String json) {
        var mapper = EmbabelObjectMapperHolder.createDefault().get();
        when(llmOperations.doTransform(anyList(), any(LlmInteraction.class), any(Class.class), isNull()))
                .thenAnswer(call -> mapper.readValue(json, (Class) call.getArgument(2)));
    }

    @SuppressWarnings("unchecked")
    private void failEveryCall() {
        when(llmOperations.doTransform(anyList(), any(LlmInteraction.class), any(Class.class), isNull()))
                .thenThrow(new TransientAiException("provider busy"));
    }

    @Nested
    class PlatformFactory {

        @Test
        void theAutoconfigurationSuppliesTheFactoryBean() {
            modelReplies("{\"verdict\":\"TRUE\"}");
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                var factory = context.getBean(LlmDecisionServiceFactory.class);
                assertThat(factory.decisionService("gpt-test").assess(proposition))
                        .isEqualTo(new PropositionResult.Answered(true, new ModelProvenance("gpt-test", "TestProvider")));
            });
        }

        @Test
        void theFactoryRecordsObservationsInTheApplicationRegistry() {
            modelReplies("{\"verdict\":\"TRUE\"}");
            var recorder = new Recorder();
            var registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(recorder);
            runner.withBean(ObservationRegistry.class, () -> registry).run(context -> {
                context.getBean(LlmDecisionServiceFactory.class).decisionService("gpt-test").assess(proposition);
                assertThat(recorder.stopped).extracting(Observation.Context::getName).containsExactly("embabel.ai.decision");
            });
        }

        @Test
        void anApplicationFactoryBeanReplacesThePlatformOne() {
            var own = mock(LlmDecisionServiceFactory.class);
            runner.withBean(LlmDecisionServiceFactory.class, () -> own)
                    .run(context -> assertThat(context.getBean(LlmDecisionServiceFactory.class)).isSameAs(own));
        }

        @Test
        void noFactoryIsSuppliedWithoutThePlatformModelBeans() {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(LlmDecisionServicesAutoConfiguration.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(LlmDecisionServiceFactory.class);
                    });
        }
    }

    @Nested
    class BoundRetry {

        @Test
        void boundMaxAttemptsLimitsTheCallsAServiceMakes() {
            failEveryCall();
            runner.withPropertyValues(quickRetry).run(context -> {
                var service = context.getBean(LlmDecisionServiceFactory.class).decisionService("gpt-test");
                assertThat(service.assess(proposition)).isEqualTo(new PropositionResult.Failure(FailureReason.UNAVAILABLE));
                verify(llmOperations, times(2)).doTransform(anyList(), any(LlmInteraction.class), any(), isNull());
            });
        }

        @Test
        void theFactoryNamesTheRetryPropertyWhenRetriesRunOut() {
            failEveryCall();
            var classification = ClassificationRequest.of(
                    "My card was charged twice",
                    ClassificationSpec.builder()
                            .asking("Which team should handle this?")
                            .category("billing", "Payments, invoices and refunds")
                            .category("technical", "Errors and outages")
                            .build());
            runner.withPropertyValues(quickRetry).run(context -> {
                var factory = context.getBean(LlmDecisionServiceFactory.class);
                var warnings = capturingWarnings(() -> {
                    factory.decisionService("gpt-test").assess(proposition);
                    factory.classificationService("gpt-test").classify(classification);
                });
                assertThat(warnings).hasSize(2);
                assertThat(warnings.get(0)).startsWith("LLM invocation decision-gpt-test:");
                assertThat(warnings.get(1)).startsWith("LLM invocation classification-gpt-test:");
                assertThat(warnings).allSatisfy(warning ->
                        assertThat(warning).endsWith("using property embabel.agent.platform.decisions.llm.max-attempts"));
            });
        }

        private List<String> capturingWarnings(Runnable block) {
            var logger = (Logger) LoggerFactory.getLogger(RetryProperties.class);
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
                    .filter(event -> event.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    private static final class Recorder implements ObservationHandler<Observation.Context> {

        final List<Observation.Context> stopped = new ArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }
    }
}
