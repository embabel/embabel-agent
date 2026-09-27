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
package com.embabel.common.ai.decision;

import static org.junit.jupiter.api.Assertions.*;

import com.embabel.common.ai.classification.Category;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

class DecisionSpecJavaTest {

    @Test
    void builderDeclaresQuestionsWithKindSpecificCustomizers() {
        // tag::builder[]
        var triage = DecisionSpec.builder()
            .proposition("is_urgent", question -> question
                .asking("Does this convey urgency?"))
            .choice("department", question -> question
                .asking("Which team should handle this?")
                .option("billing", "Payments, invoicing, refunds")
                .option("technical", "Bugs, outages, integrations")
                .option("sales", "Pricing, upgrades, new accounts"))
            .rating("frustration", question -> question
                .asking("How frustrated is the customer?")
                .level("Calm")
                .level("Frustrated")
                .level("Very angry"))
            .build();
        // end::builder[]

        List<Question<?>> questions = triage.getQuestions();
        assertEquals(3, questions.size());
        assertEquals("is_urgent", questions.get(0).getName());
        assertEquals(QuestionKind.PROPOSITION, questions.get(0).getKind());

        var department = assertInstanceOf(ChoiceQuestionSpec.class, triage.question("department"));
        assertEquals(
            List.of(
                new Category("billing", "Payments, invoicing, refunds"),
                new Category("technical", "Bugs, outages, integrations"),
                new Category("sales", "Pricing, upgrades, new accounts")),
            department.getOptions());

        var frustration = assertInstanceOf(RatingQuestionSpec.class, triage.question("frustration"));
        assertEquals(3, frustration.getLevels().size());
        assertNull(triage.question("missing"));
        assertTrue(triage.getDefinitionId().startsWith("s1-"));
    }

    @Test
    void namedQuestionsMakeAnEqualSpec() {
        // tag::named[]
        var urgent = Questions.named("is_urgent")
            .proposition("Does this convey urgency?")
            .build();

        var department = Questions.named("department")
            .choice("Which team should handle this?")
            .option("billing", "Payments, invoicing, refunds")
            .option("technical", "Bugs, outages, integrations")
            .option("sales", "Pricing, upgrades, new accounts")
            .build();

        var triage = DecisionSpec.of(urgent, department);
        // end::named[]

        var declared = DecisionSpec.builder()
            .proposition("is_urgent", question -> question.asking("Does this convey urgency?"))
            .choice("department", question -> question
                .asking("Which team should handle this?")
                .option("billing", "Payments, invoicing, refunds")
                .option("technical", "Bugs, outages, integrations")
                .option("sales", "Pricing, upgrades, new accounts"))
            .build();

        assertEquals(triage, declared);
        assertEquals(triage.getDefinitionId(), declared.getDefinitionId());
        assertEquals(triage, DecisionSpec.of(List.of(urgent, department)));
        assertEquals(triage, DecisionSpec.builder().question(urgent).question(department).build());
        assertSame(urgent, triage.question("is_urgent"));
    }

    @Test
    void aCustomizerTypedForASupertypeIsAccepted() {
        Consumer<Object> nothing = question -> { };
        var builder = DecisionSpec.builder();
        var error = assertThrows(IllegalArgumentException.class, () -> builder.choice("department", nothing));
        assertTrue(error.getMessage().contains("'department'"));
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void aThrowingCustomizerLeavesTheBuilderUnchanged() {
        var builder = DecisionSpec.builder()
            .proposition("is_urgent", question -> question.asking("Does this convey urgency?"));
        var before = builder.build();
        var thrown = new IllegalStateException("boom");
        var error = assertThrows(IllegalStateException.class, () -> builder.rating("frustration", question -> {
            question.asking("How frustrated?").level("Calm").level("Angry");
            throw thrown;
        }));
        assertSame(thrown, error);
        assertEquals(before, builder.build());
    }

    @Test
    void aDuplicateNameIsRejected() {
        var builder = DecisionSpec.builder()
            .proposition("is_urgent", question -> question.asking("Does this convey urgency?"));
        var error = assertThrows(IllegalArgumentException.class, () -> builder
            .proposition("is_urgent", question -> question.asking("Does this convey urgency?")));
        assertTrue(error.getMessage().contains("'is_urgent'"));
        assertEquals(1, builder.build().getQuestions().size());
    }
}
