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
package com.embabel.agent.api.decision.example;

import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionAnswer;
import com.embabel.common.ai.decision.DecisionCapabilities;
import com.embabel.common.ai.decision.DecisionRequest;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.QuestionKind;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.UnsupportedDecisionException;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.embabel.common.ai.model.DecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Support-triage examples: selecting a decision service, how a request reaches it, converting a
 * response to an application route and storing the response as evidence.
 */
class SupportTriageExampleTest {

    private static final String TICKET = "I was charged twice for my subscription and need a refund today.";

    private static final ModelProvenance MODEL = new ModelProvenance("triage-model", "stub");

    private static final PropositionQuestionSpec URGENT =
        Questions.named("urgent").proposition("Does the customer need help today?").build();

    private static final ChoiceQuestionSpec DEPARTMENT = Questions.named("department")
        .choice("Which team should handle the ticket?")
        .option("billing", "Payments, invoices and refunds")
        .option("technical", "Errors, outages and integrations")
        .build();

    private static final DecisionSpec TRIAGE = DecisionSpec.of(URGENT, DEPARTMENT);

    private static final PropositionResult URGENT_ANSWER = new PropositionResult.Answered(true, MODEL);

    private static final ClassificationResult BILLING = new ClassificationResult.Selected("billing", MODEL);

    private final StubDecisionService stub = StubDecisionService.builder("triage-stub")
        .proposition("urgent", URGENT_ANSWER)
        .choice("department", BILLING)
        .build();

    private final DecisionServiceRegistry registry = DecisionServiceRegistry.builder()
        .register("triage-stub", stub)
        .decisionRole("support-triage", "triage-stub")
        .build();

    private final Ai ai = ExampleOperations.withRegistry(registry).ai();

    // A service reporting the capabilities of one that implements no execution hooks.
    private static StubDecisionService legacyStub() {
        return StubDecisionService.builder("legacy-stub")
            .capabilities(DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION)))
            .proposition("urgent", URGENT_ANSWER)
            .perQuestion()
            .build();
    }

    @Test
    void selectorsReturnTheRegisteredService() {
        // tag::selectors[]
        DecisionService byRole = ai.decisions().byRole("support-triage");
        DecisionService byName = ai.decisions().named("triage-stub");
        DecisionService supplied = ai.decisions().using(stub);

        DecisionResponse response = byRole.ask(TICKET, TRIAGE);
        PropositionResult urgent = response.answer(URGENT);
        ClassificationResult department = response.answer(DEPARTMENT);
        // end::selectors[]

        assertEquals(URGENT_ANSWER, urgent);
        assertEquals(BILLING, department);
        for (DecisionService service : List.of(byRole, byName, supplied)) {
            assertEquals("triage-stub", service.getName());
            assertEquals(BILLING, service.ask(TICKET, TRIAGE).answer(DEPARTMENT));
        }
    }

    @Test
    void aNativeServiceAnswersInOneCallAndAnyOtherPerQuestion() {
        // tag::routing[]
        DecisionRequest request = DecisionRequest.of(TICKET, URGENT, DEPARTMENT);

        // The triage stub answers the whole request in one native call.
        DecisionService triage = ai.decisions().byRole("support-triage");
        DecisionResponse shared = triage.ask(request);

        // This stub answers one question per call, through assess and classify, in spec order.
        StubDecisionService perQuestion = StubDecisionService.builder("triage-per-question")
            .proposition("urgent", URGENT_ANSWER)
            .choice("department", BILLING)
            .perQuestion()
            .build();
        DecisionResponse separate = perQuestion.ask(request);
        // end::routing[]

        assertEquals(List.of("askNative"), stub.calls());
        assertEquals(List.of("assess", "classify"), perQuestion.calls());
        assertEquals(shared.getAnswers(), separate.getAnswers());
        assertEquals(URGENT_ANSWER, separate.answer(URGENT));
        assertEquals(BILLING, separate.answer(DEPARTMENT));
    }

    @Test
    void aPropositionsOnlyServiceAnswersSeveralPropositionsPerQuestion() {
        PropositionQuestionSpec refund = Questions.named("refund").proposition("Does the customer ask for a refund?").build();
        StubDecisionService scripted = StubDecisionService.builder("legacy-stub")
            .capabilities(DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION)))
            .proposition("urgent", URGENT_ANSWER)
            .proposition("refund", URGENT_ANSWER)
            .perQuestion()
            .build();

        DecisionResponse response = scripted.ask(DecisionRequest.of(TICKET, URGENT, refund));

        assertEquals(URGENT_ANSWER, response.answer(refund));
        assertEquals(List.of("assess", "assess"), scripted.calls());
    }

    @Test
    void aChoiceQuestionOnALegacyServiceThrowsBeforeAnyCall() {
        StubDecisionService legacy = legacyStub();

        UnsupportedDecisionException error = assertThrows(
            UnsupportedDecisionException.class, () -> legacy.ask(TICKET, TRIAGE));

        assertTrue(error.getMessage().contains("legacy-stub"), error.getMessage());
        assertTrue(error.getMessage().contains("capabilities leave out CHOICE questions"), error.getMessage());
        assertTrue(error.getMessage().contains("Report CHOICE in the service's capabilities()"), error.getMessage());
        assertEquals(List.of(), legacy.calls());
    }

    // tag::converter[]
    record SupportRoute(String queue, boolean sameDay, Double confidence) {
    }

    static final class SupportRouteConverter implements Converter<DecisionResponse, SupportRoute> {

        @Override
        public SupportRoute convert(DecisionResponse response) {
            boolean sameDay = switch (response.answer(URGENT)) {
                case PropositionResult.Answered answered -> answered.getAnswer();
                // Without an answer, a person looks at the ticket the same day.
                case PropositionResult.Inconclusive inconclusive -> true;
                case PropositionResult.Failure failure -> true;
            };
            // Confidence is carried for display when the provider reports it. Routing does not read it.
            return switch (response.answer(DEPARTMENT)) {
                case ClassificationResult.Selected selected ->
                    new SupportRoute(selected.getCategoryId(), sameDay, selected.getConfidence());
                case ClassificationResult.NoMatch noMatch -> new SupportRoute("general", sameDay, null);
                case ClassificationResult.Inconclusive inconclusive -> new SupportRoute("human-review", sameDay, null);
                case ClassificationResult.Failure failure -> new SupportRoute("retry-later", sameDay, null);
            };
        }
    }
    // end::converter[]

    private static DecisionResponse respond(PropositionResult urgent, ClassificationResult department) {
        return DecisionResponse.builder(TRIAGE)
            .answer(URGENT, urgent)
            .answer(DEPARTMENT, department)
            .build();
    }

    @Test
    void converterRoutesEveryOutcome() {
        SupportRouteConverter converter = new SupportRouteConverter();
        PropositionResult notUrgent = new PropositionResult.Answered(false, MODEL);

        assertEquals(new SupportRoute("billing", true, null), converter.convert(respond(URGENT_ANSWER, BILLING)));
        // A low confidence does not change the route.
        assertEquals(
            new SupportRoute("technical", false, 0.2),
            converter.convert(respond(notUrgent, new ClassificationResult.Selected("technical", MODEL, 0.2))));
        assertEquals(
            new SupportRoute("general", false, null),
            converter.convert(respond(notUrgent, new ClassificationResult.NoMatch(MODEL))));
        assertEquals(
            new SupportRoute("human-review", true, null),
            converter.convert(respond(new PropositionResult.Inconclusive(MODEL), new ClassificationResult.Inconclusive(MODEL))));
        assertEquals(
            new SupportRoute("retry-later", true, null),
            converter.convert(DecisionResponse.failed(TRIAGE, FailureReason.UNAVAILABLE)));
    }

    // tag::persist[]
    record AnswerProvenance(String question, String outcome, String modelName, String provider) {
    }

    record TriageEvidence(
        String responseJson,
        List<AnswerProvenance> answers) {
    }

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final List<TriageEvidence> evidenceStore = new ArrayList<>();

    TriageEvidence persist(DecisionResponse response) {
        List<AnswerProvenance> answers = response.getAnswers().stream()
            .map(SupportTriageExampleTest::provenanceOf)
            .toList();
        TriageEvidence evidence = new TriageEvidence(
            mapper.writeValueAsString(response),
            answers);
        evidenceStore.add(evidence);
        return evidence;
    }

    static AnswerProvenance provenanceOf(DecisionAnswer answer) {
        Object outcome = switch (answer) {
            case DecisionAnswer.Proposition proposition -> proposition.getOutcome();
            case DecisionAnswer.Choice choice -> choice.getOutcome();
            case DecisionAnswer.Rating rating -> rating.getOutcome();
        };
        ModelProvenance model = switch (outcome) {
            case PropositionResult.Answered answered -> answered.getProvenance();
            case PropositionResult.Inconclusive inconclusive -> inconclusive.getProvenance();
            case ClassificationResult.Selected selected -> selected.getProvenance();
            case ClassificationResult.NoMatch noMatch -> noMatch.getProvenance();
            case ClassificationResult.Inconclusive inconclusive -> inconclusive.getProvenance();
            case RatingResult.Answered answered -> answered.getProvenance();
            case RatingResult.Inconclusive inconclusive -> inconclusive.getProvenance();
            // A failure outcome has no model provenance.
            default -> null;
        };
        return new AnswerProvenance(
            answer.getName(),
            outcome.getClass().getSimpleName(),
            model == null ? null : model.getModelName(),
            model == null ? null : model.getProvider());
    }
    // end::persist[]

    @Test
    void persistsTheResponseAsApplicationEvidence() {
        DecisionResponse response = ai.decisions().byRole("support-triage").ask(TICKET, TRIAGE);

        TriageEvidence evidence = persist(response);

        assertEquals(List.of(evidence), evidenceStore);
        assertEquals(
            List.of(
                new AnswerProvenance("urgent", "Answered", "triage-model", "stub"),
                new AnswerProvenance("department", "Selected", "triage-model", "stub")),
            evidence.answers());
        // The stored JSON reads back to an equal response without the spec.
        assertEquals(response, mapper.readValue(evidence.responseJson(), DecisionResponse.class));
        assertTrue(!evidence.responseJson().contains(TICKET), "the response JSON holds no input");
    }

    @Test
    void aFailedRequestIsStoredWithoutProvenance() {
        TriageEvidence evidence = persist(DecisionResponse.failed(TRIAGE, FailureReason.UNAVAILABLE));

        AnswerProvenance urgent = evidence.answers().get(0);
        assertEquals("Failure", urgent.outcome());
        assertNull(urgent.modelName());
        assertNull(urgent.provider());
        assertEquals(1, evidenceStore.size());
    }
}
