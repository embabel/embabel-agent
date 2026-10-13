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
package com.embabel.agent.typesafe.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;

import com.embabel.agent.api.channel.DevNullOutputChannel;
import com.embabel.agent.api.common.Asyncer;
import com.embabel.agent.autoconfigure.models.typesafe.AgentTypeSafeAutoConfiguration;
import com.embabel.agent.autoconfigure.platform.DecisionServiceRegistryAutoConfiguration;
import com.embabel.agent.autoconfigure.platform.LlmDecisionServicesAutoConfiguration;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.ProcessContext;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.internal.LlmOperations;
import com.embabel.agent.core.support.DefaultAgentPlatform;
import com.embabel.agent.core.support.LlmInteraction;
import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.common.RetryProperties;
import com.embabel.agent.spi.config.spring.AgentPlatformConfiguration;
import com.embabel.agent.spi.config.spring.ContextRepositoryProperties;
import com.embabel.agent.spi.decision.LlmDecisionServiceFactory;
import com.embabel.agent.spi.support.ExecutorAsyncer;
import com.embabel.agent.spi.support.RankingProperties;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.agent.test.integration.IntegrationTestUtils;
import com.embabel.agent.test.unit.FakeOperationContext;
import com.embabel.agent.test.unit.FakeOperationContextKt;
import com.embabel.agent.typesafe.TypeSafeModelFactory;
import com.embabel.common.ai.autoconfig.ProviderInitialization;
import com.embabel.common.ai.autoconfig.RegisteredModel;
import com.embabel.common.ai.classification.Category;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ClassificationService;
import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.spi.DecisionContentCapture;
import com.embabel.common.ai.decision.spi.DecisionExecution;
import com.embabel.common.ai.decision.spi.PropositionAssessment;
import com.embabel.common.ai.decision.spi.RatingAssessment;
import com.embabel.common.ai.decision.support.NoOpDecisionService;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import com.embabel.common.ai.model.ModelType;
import com.embabel.common.ai.model.ServiceSelectionException;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Spring assembly of the decision services in {@code decision-services-example.yml}: the Jev catalog
 * model the TypeSafe starter registers, prompted services for chat models, and stub and no-op
 * application beans, booted through the agent platform configuration and the TypeSafe
 * auto-configuration.
 */
/*
 * These tests inspect the process-wide decision content-capture switch. This class and the
 * observation tests also change the shared com.embabel logger level and appenders in capture().
 */
@ResourceLock("DecisionContentCapture")
class DecisionServicesSpringExampleTest {
    static final String SYSTEM_ONE_URI = "https://api.typesafe.ai/v1/systemone";
    static final String FEEDBACK = "My card was charged twice and nobody answers my emails.";
    static final String SENTINEL_INPUT = "SENTINEL_INPUT my card was charged twice";
    static final String SENTINEL_KEY = "SENTINEL_KEY_value";
    static final String SENTINEL_BODY = "SENTINEL_BODY upstream detail";

    // Model names from the example YAML.
    static final String JEV_MODEL = "jev-latest";
    static final String REVIEW_MODEL = "gpt-4.1-mini";
    static final String REVIEW_PROVIDER = "ReviewProvider";

    // The System One service class is internal to its module, so its logger is named here.
    static final String SYSTEM_ONE_LOGGER = "com.embabel.agent.systemone.SystemOneDecisionService";

    // Caller identifiers that may appear in logs and span events and must not become tag values.
    static final List<String> ROLES_AND_QUESTIONS =
            List.of(
                    "support-triage",
                    "dice-revision-review",
                    "dice-revision",
                    "ticket-routing",
                    "urgent",
                    "department",
                    "frustration");

    static final PropositionQuestionSpec URGENT =
            Questions.named("urgent").proposition("Does this convey urgency?").build();

    static final ChoiceQuestionSpec DEPARTMENT =
            Questions.named("department")
                    .choice("Which team should handle this?")
                    .option("billing", "Payments, invoicing, refunds")
                    .option("technical", "Bugs, outages, integrations")
                    .build();

    static final RatingQuestionSpec FRUSTRATION =
            Questions.named("frustration")
                    .rating("How frustrated is the customer?")
                    .level("calm", "Calm")
                    .level("frustrated", "Frustrated")
                    .level("very_angry", "Very angry")
                    .build();

    static final DecisionSpec TRIAGE = DecisionSpec.of(URGENT, DEPARTMENT, FRUSTRATION);

    static final String THREE_ANSWERS =
            """
            {"model":"jev-2026-09","answers":{
              "q1":{"type":"noul","noul":0.8},
              "q2":{"type":"choice","choice":"billing","probabilities":{"billing":0.9,"technical":0.1},"confidence":0.8},
              "q3":{"type":"score","score":1.6,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},"probabilities":{"0":0.1,"1":0.2,"2":0.7},"confidence":0.4}}}
            """;

    static final String PROMPTED_ANSWERS =
            "{\"answers\":["
                    + "{\"question\":\"q1\",\"verdict\":\"TRUE\"},"
                    + "{\"question\":\"q2\",\"verdict\":\"SELECTED\",\"categoryId\":\"technical\"},"
                    + "{\"question\":\"q3\",\"verdict\":\"RATED\",\"levelId\":\"very_angry\"}]}";

    private static final ModelProvenance STUB_PROVENANCE = new ModelProvenance("triage-stub", "stub");

    private final Fixture fixture = new Fixture();

    @Test
    void starterRegistersTheCatalogModelAsTheCandidateDefault() {
        fixture.baseRunner()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var registry = context.getBean(DecisionServiceRegistry.class);
                            var jev = context.getBean(JEV_MODEL, DecisionService.class);

                            assertThat(context.getBean("typeSafeModelsInitializer", ProviderInitialization.class)
                                            .getRegisteredDecisionModels())
                                    .extracting(RegisteredModel::getBeanName, RegisteredModel::getModelId)
                                    .containsExactly(tuple(JEV_MODEL, JEV_MODEL));
                            assertThat(jev.getName()).isEqualTo(JEV_MODEL);
                            assertThat(jev.getProvider()).isEqualTo(TypeSafeModelFactory.PROVIDER);
                            assertThat(registry.registrationNames()).contains(JEV_MODEL).doesNotContain(REVIEW_MODEL);
                            assertThat(registry.decisions().defaultService()).isSameAs(jev);
                            assertThat(registry.classifications().defaultService()).isSameAs(jev);
                        });
    }

    @Test
    void yamlConfigurationResolvesTheCatalogDefaultRolesAndChatModels() {
        fixture.run(
                context -> {
                    var registry = context.getBean(DecisionServiceRegistry.class);
                    var decisions = registry.decisions();
                    var jev = context.getBean(JEV_MODEL);

                    assertThat(registry.registrationNames()).contains(JEV_MODEL, "triage-stub", "disabled");
                    assertThat(decisions.defaultService()).isSameAs(jev);
                    assertThat(registry.classifications().defaultService()).isSameAs(jev);
                    assertThat(decisions.byRole("support-triage")).isSameAs(jev);
                    assertThat(decisions.named(JEV_MODEL).getProvider()).isEqualTo(TypeSafeModelFactory.PROVIDER);
                    assertThat(decisions.named("disabled")).isInstanceOf(NoOpDecisionService.class);
                    DecisionService perUser = StubDecisionService.builder("per-user").build();
                    assertThat(decisions.using(perUser)).isSameAs(perUser);
                    assertThatThrownBy(() -> decisions.named("missing"))
                            .isInstanceOfSatisfying(
                                    ServiceSelectionException.class,
                                    e -> assertThat(e.getReason())
                                            .isEqualTo(ServiceSelectionException.Reason.UNKNOWN_NAME));

                    // A chat model role is built by LlmDecisionServiceFactory once and serves both families.
                    assertThat(context.getBean(LlmDecisionServiceFactory.class).modelNames()).contains(REVIEW_MODEL);
                    var review = decisions.byRole("dice-revision-review");
                    assertThat(review.getName()).isEqualTo(REVIEW_MODEL);
                    assertThat(review.getProvider()).isEqualTo(REVIEW_PROVIDER);
                    assertThat(decisions.named(REVIEW_MODEL)).isSameAs(review);
                    ClassificationService revision = registry.classifications().byRole("dice-revision");
                    assertThat(revision).isSameAs(review);
                    assertThat(revision.getType()).isEqualTo(ModelType.DECISION);
                    assertThat(registry.registrationNames()).doesNotContain(REVIEW_MODEL);

                    assertThat(registry.getObservationRegistry())
                            .isSameAs(context.getBean(ObservationRegistry.class))
                            .isSameAs(fixture.observations);
                    assertThat(DecisionContentCapture.isEnabled()).isFalse();
                });
        verifyNoInteractions(fixture.llmOperations);
    }

    @Test
    void llmDecisionsYamlKeepsTheCatalogDefaultAndRoutesRolesToChatModels() {
        fixture.runner("llm-decisions-example.yml")
                .withBean("small-chat-model", LlmService.class, () -> chatModel("small-chat-model"))
                .withBean("fast-chat-model", LlmService.class, () -> chatModel("fast-chat-model"))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var registry = context.getBean(DecisionServiceRegistry.class);
                            var jev = context.getBean(JEV_MODEL);

                            assertThat(registry.decisions().defaultService()).isSameAs(jev);
                            assertThat(registry.classifications().defaultService()).isSameAs(jev);
                            var triage = registry.decisions().byRole("triage");
                            assertThat(triage.getName()).isEqualTo("small-chat-model");
                            assertThat(triage.getProvider()).isEqualTo(REVIEW_PROVIDER);
                            var routing = registry.classifications().byRole("routing");
                            assertThat(routing.getName()).isEqualTo("fast-chat-model");
                            assertThat(routing).isInstanceOf(DecisionService.class);
                            assertThat(registry.registrationNames())
                                    .contains(JEV_MODEL)
                                    .doesNotContain("small-chat-model", "fast-chat-model");
                            assertThat(context.getBeansOfType(RetryProperties.class).values())
                                    .filteredOn(retry -> retry.getPropertyPrefix().equals("embabel.agent.platform.decisions.llm"))
                                    .singleElement()
                                    .satisfies(retry -> {
                                        assertThat(retry.getMaxAttempts()).isEqualTo(4);
                                        assertThat(retry.getBackoffMillis()).isEqualTo(200);
                                        assertThat(retry.getBackoffMultiplier()).isEqualTo(2.0);
                                        assertThat(retry.getBackoffMaxInterval()).isEqualTo(10000);
                                    });
                        });
        verifyNoInteractions(fixture.llmOperations);
    }

    @Test
    void explicitDecisionDefaultCanNameAChatModel() {
        fixture.runner()
                .withPropertyValues("embabel.models.decision.default=" + REVIEW_MODEL)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var registry = context.getBean(DecisionServiceRegistry.class);
                            var review = registry.decisions().defaultService();
                            assertThat(review.getName()).isEqualTo(REVIEW_MODEL);
                            assertThat(review.getProvider()).isEqualTo(REVIEW_PROVIDER);
                            assertThat(registry.decisions().named(REVIEW_MODEL)).isSameAs(review);
                            assertThat(registry.classifications().defaultService())
                                    .isSameAs(context.getBean(JEV_MODEL));
                            assertThat(registry.decisions().byRole("support-triage"))
                                    .isSameAs(context.getBean(JEV_MODEL));
                        });
    }

    @Test
    void roleNamingNoServiceOrChatModelFailsStartupNamingTheProperty() {
        fixture.runner()
                .withPropertyValues("embabel.models.decision.roles.review=gpt-missing")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasStackTraceContaining("embabel.models.decision.roles.review")
                                    .hasStackTraceContaining("gpt-missing")
                                    .hasStackTraceContaining(
                                            "Provider models: " + LlmDecisionServiceFactory.PROVIDER + " [" + REVIEW_MODEL + "]");
                        });
    }

    @Test
    void registrySummaryListsEveryRegistrationWithItsCapabilities() {
        var events = capture(() -> fixture.run(context -> context.getBean(DecisionServiceRegistry.class)));

        var summaries =
                messages(events, DecisionServiceRegistry.class.getName(), Level.INFO).stream()
                        .filter(message -> message.startsWith("Decision service registry built"))
                        .toList();
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0))
                .contains(
                        JEV_MODEL + " (name " + JEV_MODEL + ", provider TypeSafe",
                        "triage-stub (name triage-stub, provider stub",
                        "disabled (name disabled, provider none",
                        "capabilities kinds [PROPOSITION, CHOICE, RATING]",
                        "decision default: '" + JEV_MODEL + "' (explicit)",
                        "support-triage=" + JEV_MODEL,
                        "dice-revision-review=" + REVIEW_MODEL,
                        "classification default: '" + JEV_MODEL + "' (default candidate)",
                        "ticket-routing=" + REVIEW_MODEL,
                        "default candidates: [" + JEV_MODEL + "]");
        assertNoSentinels(events);
    }

    @Test
    void applicationBeansRegisterUnderTheirBeanNamesAndAnswerThroughTheRegistry() {
        fixture.runner()
                .withPropertyValues("embabel.models.decision.roles.tests=triage-stub")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var decisions = context.getBean(DecisionServiceRegistry.class).decisions();
                            assertThat(decisions.named("triage-stub")).isSameAs(context.getBean("triage-stub"));
                            assertThat(decisions.byRole("tests")).isSameAs(context.getBean("triage-stub"));
                            assertThat(decisions.named("disabled")).isSameAs(context.getBean("disabled"));

                            var stubbed = decisions.byRole("tests").ask(FEEDBACK, TRIAGE);
                            assertThat(stubbed.answer(URGENT))
                                    .isEqualTo(new PropositionResult.Answered(true, STUB_PROVENANCE));
                            assertThat(stubbed.answer(DEPARTMENT))
                                    .isEqualTo(new ClassificationResult.Selected("technical", STUB_PROVENANCE));

                            var disabled = decisions.named("disabled").ask(FEEDBACK, TRIAGE);
                            assertThat(disabled.getRequestFailure()).isEqualTo(FailureReason.UNAVAILABLE);
                            assertThat(disabled.answer(URGENT))
                                    .isEqualTo(new PropositionResult.Failure(FailureReason.UNAVAILABLE));
                            assertThat(disabled.answer(FRUSTRATION))
                                    .isEqualTo(new RatingResult.Failure(FailureReason.UNAVAILABLE));
                        });
        fixture.server.verify();
        verifyNoInteractions(fixture.llmOperations);
    }

    @Test
    void questionSetJevAskMakesOneHttpCall() {
        expectQuestionSetAnswers(fixture.server);

        fixture.run(context -> assertQuestionSetAnswers(askTriage(context, FEEDBACK)));

        fixture.server.verify();
        verifyNoInteractions(fixture.llmOperations);
    }

    @Test
    void jevQuestionHooksMakeOneHttpCallPerQuestion() {
        expectPerQuestionAnswers(fixture.server);

        fixture.run(context -> assertPerQuestionAnswers(askEachQuestion(context)));

        fixture.server.verify();
    }

    @Test
    void chatModelRoleAsksTheModelOnce() {
        answerPrompts(fixture);

        fixture.run(
                context -> assertPromptedAnswers(
                        context.getBean(DecisionServiceRegistry.class)
                                .decisions()
                                .byRole("dice-revision-review")
                                .ask(FEEDBACK, TRIAGE)));

        verifyOnePrompt(fixture);
        fixture.server.verify();
    }

    @Test
    void classificationRoleNamingAChatModelServesItsRoleAndTheSpecBean() {
        fixture.runner()
                .withUserConfiguration(TicketRoutingConfiguration.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var registry = context.getBean(DecisionServiceRegistry.class);

                            ClassificationService routing = registry.classifications().byRole("ticket-routing");
                            assertThat(routing.getType()).isEqualTo(ModelType.DECISION);
                            assertThat(routing.getName()).isEqualTo(REVIEW_MODEL);
                            assertThat(registry.decisions().named(REVIEW_MODEL)).isSameAs(routing);

                            assertThat(context.getBean(ClassificationSpec.class).getCategories())
                                    .extracting(Category::getId)
                                    .containsExactly("billing", "technical");
                        });
        verifyNoInteractions(fixture.llmOperations);
    }

    // tag::spec-bean[]
    @Configuration(proxyBeanMethods = false)
    static class TicketRoutingConfiguration {

        @Bean
        ClassificationSpec departments() {
            return ClassificationSpec.builder()
                    .asking("Which team should handle this?")
                    .category("billing", "Payments, invoicing, refunds")
                    .category("technical", "Bugs, outages, integrations")
                    .build();
        }
    }
    // end::spec-bean[]

    @Test
    void unavailableJevReturnsTypedFailuresAndLogsTheCauseWithoutPayload() {
        fixture.server
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(SENTINEL_BODY));

        var events = capture(() -> fixture.run(context -> assertUnavailable(askTriage(context, SENTINEL_INPUT))));

        fixture.server.verify();
        verifyNoInteractions(fixture.llmOperations);
        assertFailureLogLines(events);
        assertNoSentinels(events);
    }

    @Test
    void operationContextAiReachesTheRegisteredServices() {
        expectQuestionSetAnswers(fixture.server);

        fixture.run(
                context -> {
                    var operation = operationContext(context);
                    var jev = context.getBean(DecisionServiceRegistry.class).decisions().named(JEV_MODEL);

                    DecisionService triage = operation.ai().decisions().byRole("support-triage");
                    assertThat(triage.getName()).isEqualTo(jev.getName());
                    assertThat(triage.getProvider()).isEqualTo(jev.getProvider());
                    assertThat(triage.capabilities()).isEqualTo(jev.capabilities());
                    assertQuestionSetAnswers(triage.ask(FEEDBACK, TRIAGE));
                    assertThat(operation.ai().classifications().byRole("dice-revision").getName())
                            .isEqualTo(REVIEW_MODEL);
                });

        fixture.server.verify();
    }

    static DecisionResponse askTriage(AssertableApplicationContext context, String input) {
        return context.getBean(DecisionServiceRegistry.class).decisions().byRole("support-triage").ask(input, TRIAGE);
    }

    /** Asks each triage question on its own: assess, classify and rate on the jev service. */
    static DecisionResponse askEachQuestion(AssertableApplicationContext context) {
        DecisionService jev = context.getBean(DecisionServiceRegistry.class).decisions().named(JEV_MODEL);
        return DecisionResponse.builder(TRIAGE)
                .answer(URGENT, ((PropositionAssessment) jev).assess(FEEDBACK, URGENT))
                .answer(DEPARTMENT, jev.classify(FEEDBACK, ClassificationSpec.of(DEPARTMENT)))
                .answer(FRUSTRATION, ((RatingAssessment) jev).rate(FEEDBACK, FRUSTRATION))
                .build();
    }

    /** Scripts one TypeSafe question-set call that answers the triage spec. */
    static void expectQuestionSetAnswers(MockRestServiceServer server) {
        server.expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(jsonPath("$.questions.q1").exists())
                .andExpect(jsonPath("$.questions.q3").exists())
                .andExpect(jsonPath("$.questions.q4").doesNotExist())
                .andRespond(withSuccess(THREE_ANSWERS, MediaType.APPLICATION_JSON));
    }

    /** Scripts three one-question TypeSafe calls: assess, classify and rate. */
    static void expectPerQuestionAnswers(MockRestServiceServer server) {
        server.expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(jsonPath("$.questions.q1.type").value("noul"))
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"q1":{"type":"noul","noul":0.2}}}
                                """,
                                MediaType.APPLICATION_JSON));
        server.expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(jsonPath("$.questions.q1.type").value("choice"))
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"q1":{"type":"choice","choice":"billing","probabilities":{"billing":0.9,"technical":0.1},"confidence":0.8}}}
                                """,
                                MediaType.APPLICATION_JSON));
        server.expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(jsonPath("$.questions.q1.type").value("score"))
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"q1":{"type":"score","score":2.0,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},"probabilities":{"0":0.0,"1":0.0,"2":1.0},"confidence":1.0}}}
                                """,
                                MediaType.APPLICATION_JSON));
    }

    static void assertQuestionSetAnswers(DecisionResponse response) {
        assertThat(response.getRequestFailure()).isNull();
        assertThat(response.answer(URGENT))
                .isInstanceOfSatisfying(
                        PropositionResult.Answered.class, answered -> assertThat(answered.getAnswer()).isTrue());
        assertThat(response.answer(DEPARTMENT))
                .isInstanceOfSatisfying(
                        ClassificationResult.Selected.class,
                        selected -> assertThat(selected.getCategoryId()).isEqualTo("billing"));
        assertThat(response.answer(FRUSTRATION))
                .isInstanceOfSatisfying(
                        RatingResult.Answered.class,
                        answered -> assertThat(answered.getScore().getValue()).isEqualTo(1.6d));
    }

    static void assertPerQuestionAnswers(DecisionResponse response) {
        assertThat(response.answer(URGENT))
                .isInstanceOfSatisfying(
                        PropositionResult.Answered.class, answered -> assertThat(answered.getAnswer()).isFalse());
        assertThat(response.answer(DEPARTMENT)).isInstanceOf(ClassificationResult.Selected.class);
        assertThat(response.answer(FRUSTRATION)).isInstanceOf(RatingResult.Answered.class);
    }

    static void assertPromptedAnswers(DecisionResponse response) {
        assertThat(response.getRequestFailure()).isNull();
        assertThat(response.answer(URGENT)).isInstanceOf(PropositionResult.Answered.class);
        assertThat(response.answer(DEPARTMENT))
                .isInstanceOfSatisfying(
                        ClassificationResult.Selected.class,
                        selected -> assertThat(selected.getCategoryId()).isEqualTo("technical"));
        assertThat(response.answer(FRUSTRATION))
                .isInstanceOfSatisfying(
                        RatingResult.Answered.class,
                        answered -> assertThat(answered.getSelectedLevelId()).isEqualTo("very_angry"));
    }

    static void assertUnavailable(DecisionResponse response) {
        assertThat(response.getRequestFailure()).isEqualTo(FailureReason.UNAVAILABLE);
        assertThat(response.answer(URGENT)).isEqualTo(new PropositionResult.Failure(FailureReason.UNAVAILABLE));
        assertThat(response.answer(DEPARTMENT)).isEqualTo(new ClassificationResult.Failure(FailureReason.UNAVAILABLE));
        assertThat(response.answer(FRUSTRATION)).isEqualTo(new RatingResult.Failure(FailureReason.UNAVAILABLE));
    }

    /** The provider line names the cause. The execution line names the request failure. */
    static void assertFailureLogLines(List<ILoggingEvent> events) {
        assertThat(messages(events, SYSTEM_ONE_LOGGER, Level.WARN))
                .singleElement()
                .satisfies(
                        line -> assertThat(line)
                                .startsWith("System One question set failed with reason UNAVAILABLE")
                                .contains(
                                        "service=" + JEV_MODEL,
                                        "provider=TypeSafe",
                                        "cause=http_5xx",
                                        "status=5xx",
                                        "attempts=1"));
        assertThat(messages(events, DecisionExecution.class.getName(), Level.WARN))
                .singleElement()
                .satisfies(
                        line -> assertThat(line)
                                .startsWith("Decision ask failed")
                                .contains(
                                        "service=" + JEV_MODEL,
                                        "provider=TypeSafe",
                                        "requestFailure=UNAVAILABLE"));
    }

    static void answerPrompts(Fixture fixture) {
        when(fixture.llmOperations.doTransform(anyList(), any(LlmInteraction.class), eq(String.class), isNull()))
                .thenReturn(PROMPTED_ANSWERS);
    }

    static void verifyOnePrompt(Fixture fixture) {
        verify(fixture.llmOperations, times(1))
                .doTransform(anyList(), any(LlmInteraction.class), eq(String.class), isNull());
    }

    /** An operation context on the booted platform, whose platform services read the registry bean. */
    static FakeOperationContext operationContext(AssertableApplicationContext context) {
        var platformServices = context.getBean(AgentPlatform.class).getPlatformServices();
        var agent = FakeOperationContextKt.getDummyAgent();
        var processContext =
                new ProcessContext(
                        new ProcessOptions(),
                        platformServices,
                        DevNullOutputChannel.INSTANCE,
                        IntegrationTestUtils.dummyAgentProcessRunning(agent, platformServices));
        return FakeOperationContext.create(agent, processContext);
    }

    /** Captures every log event under {@code com.embabel} at DEBUG while the block runs. */
    static List<ILoggingEvent> capture(Runnable block) {
        var logger = (Logger) LoggerFactory.getLogger("com.embabel");
        var oldLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            block.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(oldLevel);
            appender.stop();
        }
        return List.copyOf(appender.list);
    }

    static List<String> messages(List<ILoggingEvent> events, String loggerName, Level level) {
        return events.stream()
                .filter(event -> event.getLoggerName().equals(loggerName))
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    static void assertNoSentinels(List<ILoggingEvent> events) {
        assertThat(events)
                .isNotEmpty()
                .allSatisfy(
                        event -> {
                            assertThat(event.getFormattedMessage()).doesNotContain("SENTINEL");
                            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                                assertThat(String.valueOf(t.getMessage())).doesNotContain("SENTINEL");
                            }
                        });
    }

    /** Asserts that no meter tag holds a payload sentinel, a role or a question name. */
    static void assertBoundedTags(SimpleMeterRegistry meters) {
        for (Meter meter : meters.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(tag.getValue()).doesNotContain("SENTINEL").isNotIn(ROLES_AND_QUESTIONS);
            }
        }
    }

    /** A stub that answers the triage spec by question name. */
    /**
     * A chat model under the given name, from the same provider as the review model.
     *
     * @param name the model name
     * @return the chat model
     */
    static LlmService<?> chatModel(String name) {
        return new SpringAiLlmService(name, REVIEW_PROVIDER, mock(ChatModel.class));
    }

    static StubDecisionService triageStub() {
        return StubDecisionService.builder("triage-stub")
                .proposition("urgent", new PropositionResult.Answered(true, STUB_PROVENANCE))
                .choice("department", new ClassificationResult.Selected("technical", STUB_PROVENANCE))
                .rating("frustration", new RatingResult.Answered(STUB_PROVENANCE, "frustrated"))
                .build();
    }

    /**
     * Collaborators for one context: a mock TypeSafe endpoint, an observation registry with a
     * recorder and a meter handler, mocked LLM operations and the review model.
     */
    static final class Fixture {
        final RestClient.Builder restClientBuilder = RestClient.builder();
        final MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final Recorder recorder = new Recorder();
        final ObservationRegistry observations = ObservationRegistry.create();
        final LlmOperations llmOperations = mock(LlmOperations.class);
        final LlmService<?> reviewModel = new SpringAiLlmService(REVIEW_MODEL, REVIEW_PROVIDER, mock(ChatModel.class));

        Fixture() {
            observations
                    .observationConfig()
                    .observationHandler(recorder)
                    .observationHandler(new DefaultMeterObservationHandler(meters));
        }

        /** The context with the example YAML. */
        ApplicationContextRunner runner() {
            return runner("decision-services-example.yml");
        }

        /**
         * The context with an example YAML file from the test classpath.
         *
         * @param yaml the classpath name of the YAML file
         * @return the runner
         */
        ApplicationContextRunner runner(String yaml) {
            return baseRunner()
                    .withInitializer(context -> loadExampleYaml(yaml, context.getEnvironment().getPropertySources()));
        }

        /** The context without the example YAML, so only the starter's defaults apply. */
        ApplicationContextRunner baseRunner() {
            return new ApplicationContextRunner()
                    .withUserConfiguration(AgentPlatformConfiguration.class)
                    .withConfiguration(AutoConfigurations.of(
                            LlmDecisionServicesAutoConfiguration.class,
                            DecisionServiceRegistryAutoConfiguration.class,
                            AgentTypeSafeAutoConfiguration.class))
                    .withPropertyValues(
                            "TYPESAFE_API_KEY=",
                            "embabel.agent.platform.models.typesafe.api-key=" + SENTINEL_KEY,
                            "embabel.models.default-llm=" + REVIEW_MODEL)
                    .withBean(RestClient.Builder.class, () -> restClientBuilder)
                    .withBean(ObservationRegistry.class, () -> observations)
                    .withBean(LlmOperations.class, () -> llmOperations)
                    .withBean(REVIEW_MODEL, LlmService.class, () -> reviewModel)
                    .withBean(RankingProperties.class, RankingProperties::new)
                    .withBean(ContextRepositoryProperties.class, ContextRepositoryProperties::new)
                    .withBean(Asyncer.class, () -> new ExecutorAsyncer(Runnable::run))
                    .withBean(DefaultAgentPlatform.class)
                    .withBean("triage-stub", DecisionService.class, DecisionServicesSpringExampleTest::triageStub)
                    .withBean("disabled", DecisionService.class, () -> new NoOpDecisionService("disabled"));
        }

        void run(Consumer<AssertableApplicationContext> assertions) {
            runner().run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                assertions.accept(context);
                            });
        }

        /**
         * Adds a YAML file as the last property source, so property values set on the runner take
         * precedence over it.
         *
         * @param yaml the classpath name of the YAML file
         * @param sources the property sources to add it to
         */
        private static void loadExampleYaml(String yaml, MutablePropertySources sources) {
            try {
                new YamlPropertySourceLoader()
                        .load(yaml, new ClassPathResource(yaml))
                        .forEach(sources::addLast);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Records every stopped observation and every observation event. */
    static final class Recorder implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
        final List<Observation.Event> events = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onEvent(Observation.Event event, Observation.Context context) {
            events.add(event);
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }

        List<Observation.Context> named(String name) {
            return stopped.stream().filter(context -> name.equals(context.getName())).toList();
        }
    }
}
