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
package com.embabel.common.ai.decision.annotated.example;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.DecisionProjection;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.annotated.AnnotatedDecision;
import com.embabel.common.ai.decision.annotated.AnnotatedDecisions;
import com.embabel.common.ai.decision.annotated.ChoiceQuestion;
import com.embabel.common.ai.decision.annotated.Described;
import com.embabel.common.ai.decision.annotated.PropositionQuestion;
import com.embabel.common.ai.decision.annotated.RatingQuestion;
import com.embabel.common.ai.decision.support.StubDecisionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Reads a support-triage decision from an annotated record and projects the answers back into
 * it, first under a plain Jackson mapper and then under a Spring Boot mapper that uses snake case.
 */
class SupportTriageAnnotatedExampleTest {

    private static final ModelProvenance PROVENANCE = new ModelProvenance("stub-model", "stub");

    private static final String TICKET = "The export API returns 500 for every customer since this morning.";

    // tag::annotated-triage[]
    enum Department {
        @Described("Payments, invoicing, refunds")
        BILLING,
        @Described("Bugs, outages, integrations")
        TECHNICAL,
        @Described("Accounts, sign-in, permissions")
        ACCOUNT,
    }

    // Rating levels run from lowest to highest in declaration order.
    enum Severity {
        LOW,
        @Described("Work is blocked for one customer")
        HIGH,
        @Described("Work is blocked for many customers")
        CRITICAL,
    }

    record TicketTriage(
        @PropositionQuestion(asking = "Does the customer need a reply within the hour?")
        boolean needsUrgentReply,
        @ChoiceQuestion(asking = "Which team should handle this ticket?")
        Department assignedDepartment,
        @RatingQuestion(asking = "How severe is the customer impact?")
        Severity customerSeverity) {
    }
    // end::annotated-triage[]

    @Test
    void plainJacksonReadsTheRecordAndProjectsTheAnswers() {
        // tag::annotated-plain[]
        AnnotatedDecision<TicketTriage> triage = AnnotatedDecisions.defaults().of(TicketTriage.class);
        DecisionResponse response = stub("needsUrgentReply", "assignedDepartment", "customerSeverity")
            .ask(TICKET, triage.spec());
        DecisionProjection<TicketTriage> projection = triage.project(response);
        // end::annotated-plain[]

        assertEquals(
            List.of("needsUrgentReply", "assignedDepartment", "customerSeverity"),
            questionNames(triage.spec()));
        assertEquals(new TicketTriage(true, Department.TECHNICAL, Severity.CRITICAL), projection.getValue());
        assertEquals(triage.spec().getDefinitionId(), projection.getResponse().getDefinitionId());
    }

    @Test
    void springSnakeCaseMapperNamesTheQuestionsAndProjectsTheAnswers() {
        AnnotatedDecision<TicketTriage> plain = AnnotatedDecisions.defaults().of(TicketTriage.class);

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withPropertyValues("spring.jackson.property-naming-strategy=SNAKE_CASE")
            .run(context -> {
                // tag::annotated-spring[]
                JsonMapper mapper = context.getBean(JsonMapper.class);
                AnnotatedDecisions decisions = AnnotatedDecisions.using(mapper);
                AnnotatedDecision<TicketTriage> triage = decisions.of(TicketTriage.class);
                // end::annotated-spring[]

                assertEquals(
                    List.of("needs_urgent_reply", "assigned_department", "customer_severity"),
                    questionNames(triage.spec()));
                assertEquals(
                    Map.of(
                        "needsUrgentReply", "needs_urgent_reply",
                        "assignedDepartment", "assigned_department",
                        "customerSeverity", "customer_severity"),
                    triage.questionNames());
                assertNotEquals(plain.spec().getDefinitionId(), triage.spec().getDefinitionId());

                DecisionResponse response = stub("needs_urgent_reply", "assigned_department", "customer_severity")
                    .ask(TICKET, triage.spec());
                assertEquals(
                    new TicketTriage(true, Department.TECHNICAL, Severity.CRITICAL),
                    triage.project(response).getValue());
            });
    }

    @Test
    void specJsonNamesNoJavaClassUnderEitherMapper() {
        JsonMapper plainMapper = JsonMapper.builder().build();
        String plainJson = plainMapper.writeValueAsString(AnnotatedDecisions.defaults().of(TicketTriage.class).spec());
        assertNoClassNames(plainJson);

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withPropertyValues("spring.jackson.property-naming-strategy=SNAKE_CASE")
            .run(context -> {
                JsonMapper mapper = context.getBean(JsonMapper.class);
                assertNoClassNames(mapper.writeValueAsString(AnnotatedDecisions.using(mapper).of(TicketTriage.class).spec()));
            });
    }

    private static StubDecisionService stub(String urgent, String department, String severity) {
        return StubDecisionService.builder("triage-stub")
            .proposition(urgent, new PropositionResult.Answered(true, PROVENANCE))
            .choice(department, new ClassificationResult.Selected("TECHNICAL", PROVENANCE))
            .rating(severity, new RatingResult.Answered(PROVENANCE, "CRITICAL"))
            .build();
    }

    private static List<String> questionNames(DecisionSpec spec) {
        return spec.getQuestions().stream().map(question -> question.getName()).toList();
    }

    private static void assertNoClassNames(String json) {
        assertFalse(json.matches("(?s).*[a-z]+\\.[a-z]+\\.[A-Z].*"), json);
        assertFalse(json.contains("@class"), json);
        assertFalse(json.contains("com.embabel") || json.contains("TicketTriage") || json.contains("$"), json);
    }
}
