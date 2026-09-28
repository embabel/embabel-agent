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
package com.embabel.common.ai.decision.annotated;

import com.embabel.common.ai.classification.CategoryMapping;
import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ClassificationSpec;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.MappedClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.embabel.common.ai.model.ClassificationService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks classification with an annotated enum, and that a decision type holding one choice
 * question reads as a classification spec.
 */
class AnnotatedClassificationTest {

    private static final ModelProvenance PROVENANCE = new ModelProvenance("stub-model", "stub");

    private static final String TEAM = "Which team should handle this ticket?";

    @Classification(asking = TEAM)
    enum Department {
        @Described("Payments, invoicing, refunds")
        BILLING,
        @Described("Bugs, outages, integrations")
        TECHNICAL,
    }

    // Ids come from the constant names, so toString does not change them.
    @Classification(asking = TEAM)
    enum Renamed {
        @Described("Payments, invoicing, refunds")
        BILLING;

        @Override
        public String toString() {
            return "billing";
        }
    }

    enum Unannotated {
        @Described("Payments, invoicing, refunds")
        BILLING,
    }

    @Classification(asking = TEAM)
    enum Empty {
    }

    @Classification(asking = " ")
    enum BlankAsking {
        @Described("Payments, invoicing, refunds")
        BILLING,
    }

    @Classification(asking = TEAM)
    enum Undescribed {
        BILLING,
        @Described("Bugs, outages, integrations")
        TECHNICAL,
        ACCOUNT,
    }

    record Routing(@ChoiceQuestion(asking = TEAM) Department department) {
    }

    record RoutingWithUrgency(
        @ChoiceQuestion(asking = TEAM) Department department,
        @PropositionQuestion(asking = "Is it urgent?") boolean urgent) {
    }

    record RoutingWithSource(String sourceId, @ChoiceQuestion(asking = TEAM) Department department) {
    }

    /** Answers every request with one scripted result and keeps the requests it received. */
    private static final class ScriptedClassifier implements ClassificationService {

        private final ClassificationResult result;

        private final List<ClassificationRequest> requests = new ArrayList<>();

        ScriptedClassifier(ClassificationResult result) {
            this.result = result;
        }

        @Override
        public String getName() {
            return "stub-model";
        }

        @Override
        public String getProvider() {
            return "stub";
        }

        @Override
        public ClassificationResult classify(ClassificationRequest request) {
            requests.add(request);
            return result;
        }
    }

    @Nested
    class Mapping {

        @Test
        void classifiesAndMapsTheSelectionToTheConstant() {
            var departments = AnnotatedDecisions.classification(Department.class);
            var classifier = new ScriptedClassifier(departments.spec().selected("TECHNICAL", PROVENANCE, 0.8));

            var result = classifier.classify("The export API returns 500.", departments.spec());

            assertEquals(
                new MappedClassificationResult.Selected<>(Department.TECHNICAL, (ClassificationResult.Selected) result),
                departments.map(result));
            assertEquals(departments.spec(), classifier.requests.getFirst().getSpec());
        }

        @Test
        void specHasOneCategoryPerConstantWithItsDescription() {
            var expected = ClassificationSpec.builder()
                .asking(TEAM)
                .category("BILLING", "Payments, invoicing, refunds")
                .category("TECHNICAL", "Bugs, outages, integrations")
                .build();

            var spec = AnnotatedDecisions.classification(Department.class).spec();

            assertEquals(expected, spec);
            assertEquals(ClassificationSpec.QUESTION_NAME, spec.getQuestion().getName());
        }

        @Test
        void mapsEverySelectionToItsConstant() {
            CategoryMapping<Department> departments = AnnotatedDecisions.classification(Department.class);
            for (Department department : Department.values()) {
                var selected = new ClassificationResult.Selected(department.name(), PROVENANCE);
                assertEquals(new MappedClassificationResult.Selected<>(department, selected), departments.map(selected));
            }
        }

        @Test
        void passesNonSelectionsThroughUnchanged() {
            var departments = AnnotatedDecisions.classification(Department.class);
            for (ClassificationResult result : List.of(
                new ClassificationResult.NoMatch(PROVENANCE),
                new ClassificationResult.Inconclusive(PROVENANCE),
                new ClassificationResult.Failure(FailureReason.UNAVAILABLE))) {
                assertSame(result, departments.map(result));
            }
        }

        @Test
        void rejectsASelectionOutsideTheCategories() {
            var departments = AnnotatedDecisions.classification(Department.class);
            var outsideCategory = new ClassificationResult.Selected("ACCOUNT", PROVENANCE);

            assertThrows(IllegalArgumentException.class, () -> departments.map(outsideCategory));
        }

        @Test
        void categoryIdsAreConstantNamesRatherThanToString() {
            assertEquals("BILLING",
                AnnotatedDecisions.classification(Renamed.class).getCategories().getFirst().getId());
        }
    }

    @Nested
    class Errors {

        @Test
        void rejectsAnEnumWithoutClassification() {
            assertOnlyProblem(Unannotated.class, "Unannotated: has no @Classification. "
                + "Annotate the enum with @Classification(asking = \"...\").");
        }

        @Test
        @SuppressWarnings({"unchecked", "rawtypes"})
        void rejectsAClassThatIsNotAnEnum() {
            Class raw = Routing.class;
            var failure = assertThrows(AnnotatedDecisionException.class, () -> AnnotatedDecisions.classification(raw));

            assertEquals(List.of("Routing: is not an enum. Declare the categories as an enum annotated with @Classification."),
                failure.problems());
        }

        @Test
        void rejectsAnEnumWithoutConstants() {
            assertOnlyProblem(Empty.class, "Empty: has no constants, so the classification has no categories. "
                + "Add one constant per category to Empty.");
        }

        @Test
        void rejectsABlankAskingValue() {
            assertOnlyProblem(BlankAsking.class, "BlankAsking: @Classification has a blank asking value. "
                + "Set asking to the instructions the model receives.");
        }

        @Test
        void reportsEveryConstantWithoutDescribed() {
            assertEquals(List.of(
                    "Undescribed.BILLING: category has no @Described. "
                        + "Add @Described with the category's description to Undescribed.BILLING.",
                    "Undescribed.ACCOUNT: category has no @Described. "
                        + "Add @Described with the category's description to Undescribed.ACCOUNT."),
                failure(Undescribed.class).problems());
        }

        private static <E extends Enum<E>> AnnotatedDecisionException failure(Class<E> type) {
            var failure = assertThrows(AnnotatedDecisionException.class, () -> AnnotatedDecisions.classification(type));
            assertSame(type, failure.type());
            return failure;
        }

        private static <E extends Enum<E>> void assertOnlyProblem(Class<E> type, String problem) {
            assertEquals(List.of(problem), failure(type).problems());
        }
    }

    @Nested
    class Cache {

        @Test
        void repeatedCallsReturnTheSameMapping() {
            assertSame(AnnotatedDecisions.classification(Department.class),
                AnnotatedDecisions.classification(Department.class));
        }

        @Test
        void aFailedReadIsNotCached() {
            var first = assertThrows(AnnotatedDecisionException.class,
                () -> AnnotatedDecisions.classification(Unannotated.class));
            var second = assertThrows(AnnotatedDecisionException.class,
                () -> AnnotatedDecisions.classification(Unannotated.class));

            assertNotSame(first, second);
        }
    }

    @Nested
    class SingleChoiceDecision {

        @Test
        void readsAsAClassificationSpecThatKeepsThePropertyName() {
            var decision = AnnotatedDecisions.defaults().of(Routing.class);

            var spec = assertInstanceOf(ClassificationSpec.class, decision.spec());

            assertEquals("department", spec.getQuestion().getName());
            assertEquals(Map.of("department", "department"), decision.questionNames());
        }

        @Test
        void projectsTheAnswerAsBefore() {
            var decision = AnnotatedDecisions.defaults().of(Routing.class);
            DecisionResponse response = StubDecisionService.builder("routing-stub")
                .choice("department", new ClassificationResult.Selected("BILLING", PROVENANCE))
                .build()
                .ask("My card was charged twice.", decision.spec());

            assertEquals(new Routing(Department.BILLING), decision.project(response).getValue());
        }

        @Test
        void writesTheSameJsonAsADecisionSpec() {
            var mapper = JsonMapper.builder().build();
            var spec = AnnotatedDecisions.defaults().of(Routing.class).spec();

            assertEquals(mapper.writeValueAsString(DecisionSpec.of(spec.getQuestions())), mapper.writeValueAsString(spec));
        }

        @Test
        void aTypeWithAnotherQuestionStaysADecisionSpec() {
            assertFalse(AnnotatedDecisions.defaults().of(RoutingWithUrgency.class).spec() instanceof ClassificationSpec);
        }

        @Test
        void aPropertyThatIsNotAQuestionStillReadsAsAClassificationSpec() {
            var decision = AnnotatedDecisions.defaults().of(RoutingWithSource.class);
            var spec = assertInstanceOf(ClassificationSpec.class, decision.spec());
            DecisionResponse response = StubDecisionService.builder("routing-stub")
                .choice("department", new ClassificationResult.Selected("TECHNICAL", PROVENANCE))
                .build()
                .ask("The export job keeps timing out.", spec);

            assertEquals(new RoutingWithSource("ticket-42", Department.TECHNICAL),
                decision.project(response, Map.of("sourceId", "ticket-42")));
        }

        @Test
        void aSingleNonChoiceQuestionStaysADecisionSpec() {
            record Urgency(@PropositionQuestion(asking = "Is it urgent?") boolean urgent) {
            }
            assertFalse(AnnotatedDecisions.defaults().of(Urgency.class).spec() instanceof ClassificationSpec);
        }
    }
}
