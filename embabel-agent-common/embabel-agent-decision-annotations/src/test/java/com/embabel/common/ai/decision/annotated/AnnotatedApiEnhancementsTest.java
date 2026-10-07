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

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.DecisionProjection;
import com.embabel.common.ai.decision.DecisionProjectionException;
import com.embabel.common.ai.decision.LevelProbability;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.RatingResult;
import com.embabel.common.ai.decision.RatingScore;
import com.embabel.common.ai.decision.RatingStatistic;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AnnotatedApiEnhancementsTest {

    private static final AnnotatedDecisions DEFAULTS = AnnotatedDecisions.defaults();

    private static final ModelProvenance PROVENANCE = new ModelProvenance("model", "provider");

    // tag::annotated-stable-ids[]
    @Classification(asking = "Which team?")
    enum Team {
        @DecisionId("billing-v1") @Described("Billing") @JsonProperty("payments")
        BILLING,
        @DecisionId("technical-v1") @Described("Technical")
        TECHNICAL,
    }

    // end::annotated-stable-ids[]

    enum Severity {
        @DecisionId("low-v1") LOW,
        @DecisionId("high-v1") HIGH,
    }

    record Routing(String sourceId,
        @ChoiceQuestion(name = "route-v1", asking = "Which team?") Team assignedTeam) {
    }

    // tag::annotated-rich-rating[]
    record RichRating(@RatingQuestion(name = "impact-v1", asking = "How severe?", levels = Severity.class)
        RatingResult impact) {
    }

    // end::annotated-rich-rating[]

    record EnumRating(@RatingQuestion(name = "impact-v1", asking = "How severe?") Severity impact) {
    }

    record Mixed(@ChoiceQuestion(asking = "Which team?") Team team,
        @PropositionQuestion(asking = "Urgent?") boolean urgent) {
    }

    record Identity(int revision, boolean reviewed, @PropositionQuestion(asking = "Urgent?") boolean urgent) {
    }

    @Test
    void sourcedProjectionKeepsResponseAndStableIdsMapBackThroughJackson() {
        var reader = AnnotatedDecisions.using(JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build());
        var decision = reader.of(Routing.class);
        var response = StubDecisionService.builder("stub")
            .choice("route-v1", new ClassificationResult.Selected("billing-v1", PROVENANCE))
            .build().ask("ticket", decision.spec());

        DecisionProjection<Routing> projection = decision.project(response, Map.of("source_id", "ticket-1"));

        assertEquals(new Routing("ticket-1", Team.BILLING), projection.getValue());
        assertSame(response, projection.getResponse());
        assertEquals(Map.of("assignedTeam", "route-v1"), decision.questionNames());
        assertEquals("billing-v1", decision.classificationSpec().getCategories().getFirst().getId());
        var mapping = AnnotatedDecisions.classification(Team.class);
        assertEquals(mapping.getCategories(), decision.classificationSpec().getCategories());
        assertEquals(Team.BILLING,
            ((com.embabel.common.ai.classification.MappedClassificationResult.Selected<Team>)
                mapping.map(new ClassificationResult.Selected("billing-v1", PROVENANCE))).getValue());
    }

    @Test
    void classificationAccessorRejectsSeveralQuestionsClearly() {
        var decision = DEFAULTS.of(Mixed.class);
        var error = assertThrows(IllegalStateException.class, decision::classificationSpec);
        assertTrue(error.getMessage().contains("exactly one choice question"));
    }

    @Test
    void richRatingRetainsJevEvidenceAndPromptedSelection() {
        var score = new RatingScore(0.75, RatingStatistic.EXPECTED_LEVEL_INDEX);
        var distribution = List.of(new LevelProbability("low-v1", 0.25), new LevelProbability("high-v1", 0.75));
        for (RatingResult outcome : List.of(
            new RatingResult.Answered(PROVENANCE, null, distribution, score, 0.8),
            new RatingResult.Answered(PROVENANCE, "high-v1"),
            new RatingResult.Inconclusive(PROVENANCE),
            new RatingResult.Failure(FailureReason.UNAVAILABLE))) {
            var decision = DEFAULTS.of(RichRating.class);
            var response = StubDecisionService.builder("stub").rating("impact-v1", outcome)
                .build().ask("ticket", decision.spec());
            var projection = decision.project(response);
            assertEquals(outcome, projection.getValue().impact());
            assertSame(response, projection.getResponse());
        }
    }

    @Test
    void enumRatingStillRequiresAProviderSelection() {
        var decision = DEFAULTS.of(EnumRating.class);
        var answered = StubDecisionService.builder("stub")
            .rating("impact-v1", new RatingResult.Answered(PROVENANCE, "high-v1"))
            .build().ask("ticket", decision.spec());
        assertEquals(Severity.HIGH, decision.project(answered).getValue().impact());
        var scored = StubDecisionService.builder("stub")
            .rating("impact-v1", new RatingResult.Answered(PROVENANCE, null, List.of(),
                new RatingScore(0.75, RatingStatistic.EXPECTED_LEVEL_INDEX)))
            .build().ask("ticket", decision.spec());
        assertThrows(DecisionProjectionException.class, () -> decision.project(scored));
    }

    @Test
    void coercionFollowsTheSuppliedMapperAndDefaultPrimitiveNullsFail() {
        var defaults = DEFAULTS.of(Identity.class);
        var response = StubDecisionService.builder("stub").proposition("urgent", new PropositionResult.Answered(true, PROVENANCE))
            .build().ask("ticket", defaults.spec());
        assertEquals(new Identity(3, true, true), defaults.project(response,
            Map.of("revision", "3", "reviewed", "true")).getValue());
        assertEquals(3, defaults.project(response, Map.of("revision", 3.0, "reviewed", true)).getValue().revision());
        Map<String, Object> nullRevision = new HashMap<>();
        nullRevision.put("revision", null);
        nullRevision.put("reviewed", true);
        assertThrows(DecisionProjectionException.class, () -> defaults.project(response, nullRevision));
        var strict = AnnotatedDecisions.using(JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build()).of(Identity.class);
        var strings = Map.of("revision", "3", "reviewed", "true");
        var floatingPoint = Map.of("revision", 3.0, "reviewed", true);
        assertThrows(DecisionProjectionException.class, () -> strict.project(response, strings));
        assertThrows(DecisionProjectionException.class, () -> strict.project(response, floatingPoint));
    }

    enum BlankDescription {
        @Described(" ") BAD,
    }

    record Invalid(@ChoiceQuestion(asking = "Choose") BlankDescription pick) {
    }

    record MissingLevels(@RatingQuestion(asking = "Rate") RatingResult rating) {
    }

    enum DuplicateIds {
        @DecisionId("same") @Described("First") FIRST,
        @DecisionId("same") @Described("Second") SECOND,
    }

    record DuplicateChoices(@ChoiceQuestion(asking = "Choose") DuplicateIds pick) {
    }

    @Test
    void diagnosticDetailsSurviveExceptionSerialization() throws Exception {
        var error = new AnnotatedDecisionException(Invalid.class,
            List.of("Invalid.pick: has a blank description"), null);
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(error);
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            var restored = (AnnotatedDecisionException) input.readObject();
            assertEquals(error.details(), restored.details());
            assertEquals(error.problems(), restored.problems());
        }
    }

    @Test
    void diagnosticsIdentifyTheTypeAndMemberForTooling() {
        var error = assertThrows(AnnotatedDecisionException.class, () -> DEFAULTS.of(Invalid.class));
        assertTrue(error.problems().getFirst().contains("blank @Described"));
        assertEquals(Invalid.class, error.details().getFirst().type());
        assertEquals("pick", error.details().getFirst().member());
        assertEquals(error.problems().getFirst(), error.details().getFirst().message());
        var details = error.details();
        assertThrows(UnsupportedOperationException.class, details::clear);
        var missing = assertThrows(AnnotatedDecisionException.class,
            () -> DEFAULTS.of(MissingLevels.class));
        assertTrue(missing.getMessage().contains("levels"));
        var duplicate = assertThrows(AnnotatedDecisionException.class,
            () -> DEFAULTS.of(DuplicateChoices.class));
        assertTrue(duplicate.getMessage().contains("same"));
    }

    record DuplicateNames(
        @PropositionQuestion(name = "same", asking = "First?") boolean first,
        @PropositionQuestion(name = "same", asking = "Second?") boolean second) {
    }

    record BlankName(@PropositionQuestion(name = " ", asking = "Urgent?") boolean urgent) {
    }

    record InvalidScale(@RatingQuestion(asking = "Rate", levels = String.class) RatingResult rating) {
    }

    record RedundantScale(@RatingQuestion(asking = "Rate", levels = Severity.class) Severity rating) {
    }

    @Classification(asking = "Choose")
    enum BlankCategory {
        @Described(" ") BAD,
    }

    @Classification(asking = "Choose")
    enum BlankId {
        @Described("Bad") @DecisionId(" ") BAD,
    }

    @Test
    void invalidNamesScalesAndCategoryMetadataFailAtDeclaration() {
        for (Class<?> type : List.of(DuplicateNames.class, BlankName.class,
            InvalidScale.class, RedundantScale.class)) {
            assertThrows(AnnotatedDecisionException.class, () -> DEFAULTS.of(type));
        }
        assertTrue(assertThrows(AnnotatedDecisionException.class,
            () -> AnnotatedDecisions.classification(BlankCategory.class)).getMessage().contains("blank @Described"));
        assertTrue(assertThrows(AnnotatedDecisionException.class,
            () -> AnnotatedDecisions.classification(BlankId.class)).getMessage().contains("blank @DecisionId"));
    }

    @Test
    void otherPropertiesCannotOverrideProviderNamesOrJacksonQuestionProperties() {
        var decision = DEFAULTS.of(Routing.class);
        var response = StubDecisionService.builder("stub")
            .choice("route-v1", new ClassificationResult.Selected("billing-v1", PROVENANCE))
            .build().ask("ticket", decision.spec());
        for (String key : List.of("route-v1", "assignedTeam")) {
            var otherProperties = Map.of("sourceId", "ticket-1", key, Team.TECHNICAL);
            assertThrows(DecisionProjectionException.class,
                () -> decision.project(response, otherProperties));
        }
    }


    record OverlappingNames(String sourceId,
        @ChoiceQuestion(name = "sourceId", asking = "Which team?") Team assignedTeam) {
    }

    @Test
    void providerNamesCanCoincideWithAnIdentityProperty() {
        var decision = DEFAULTS.of(OverlappingNames.class);
        var response = StubDecisionService.builder("stub")
            .choice("sourceId", new ClassificationResult.Selected("billing-v1", PROVENANCE))
            .build().ask("ticket", decision.spec());
        assertEquals(new OverlappingNames("ticket-1", Team.BILLING),
            decision.project(response, Map.of("sourceId", "ticket-1")).getValue());
    }

}
