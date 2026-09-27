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

import com.embabel.common.ai.classification.Category;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.RatingLevel;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.EnumNamingStrategies;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Checks that question names, option ids and level ids come from the Jackson mapper, and that each
 * {@link AnnotatedDecisions} keeps the names of its own mapper.
 */
class AnnotatedNamingTest {

    enum Department {
        @Described("Payments, invoicing, refunds")
        @JsonProperty("billing")
        BILLING,
        @Described("Bugs, outages, integrations")
        @JsonProperty("technical")
        TECHNICAL,
    }

    enum Severity {
        LOW,
        VERY_HIGH,
    }

    record ExplicitName(
        @JsonProperty("is_urgent") @PropositionQuestion(asking = "Does this ticket convey urgency?") boolean urgent) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record AnnotatedNaming(
        @PropositionQuestion(asking = "Does this ticket convey urgency?") boolean isUrgent,
        @RatingQuestion(asking = "How severe is the impact?") Severity severityLevel) {
    }

    record Plain(
        @PropositionQuestion(asking = "Does this ticket convey urgency?") boolean isUrgent,
        @ChoiceQuestion(asking = "Which team should handle this?") Department department,
        @RatingQuestion(asking = "How severe is the impact?") Severity severityLevel) {
    }

    private static final ObjectMapper CAMEL = JsonMapper.builder().build();

    private static List<String> names(DecisionSpec spec) {
        return spec.getQuestions().stream().map(question -> question.getName()).toList();
    }

    @Test
    void jsonPropertyNamesTheQuestion() {
        AnnotatedDecision<ExplicitName> decision = AnnotatedDecisions.using(CAMEL).of(ExplicitName.class);

        assertEquals(List.of("is_urgent"), names(decision.spec()));
        assertEquals(Map.of("urgent", "is_urgent"), decision.questionNames());
    }

    @Test
    void jsonNamingOnTheTypeNamesTheQuestions() {
        AnnotatedDecision<AnnotatedNaming> decision = AnnotatedDecisions.using(CAMEL).of(AnnotatedNaming.class);

        assertEquals(List.of("is_urgent", "severity_level"), names(decision.spec()));
        assertEquals(Map.of("isUrgent", "is_urgent", "severityLevel", "severity_level"), decision.questionNames());
    }

    @Test
    void mapperNamingStrategyNamesTheQuestions() {
        ObjectMapper snake = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

        DecisionSpec spec = AnnotatedDecisions.using(snake).of(Plain.class).spec();

        assertEquals(List.of("is_urgent", "department", "severity_level"), names(spec));
    }

    @Test
    void jsonPropertyOnEnumConstantsSetsOptionIds() {
        ChoiceQuestionSpec department = (ChoiceQuestionSpec) AnnotatedDecisions.using(CAMEL).of(Plain.class)
            .spec().question("department");

        assertEquals(
            List.of(
                new Category("billing", "Payments, invoicing, refunds"),
                new Category("technical", "Bugs, outages, integrations")),
            department.getOptions());
    }

    @Test
    void mapperEnumNamingStrategySetsLevelIds() {
        ObjectMapper lowerCamel = JsonMapper.builder().enumNamingStrategy(EnumNamingStrategies.LOWER_CAMEL_CASE).build();

        RatingQuestionSpec severity = (RatingQuestionSpec) AnnotatedDecisions.using(lowerCamel).of(Plain.class)
            .spec().question("severityLevel");

        assertEquals(List.of(new RatingLevel("low", "low"), new RatingLevel("veryHigh", "veryHigh")), severity.getLevels());
    }

    @Test
    void eachInstanceKeepsTheNamesOfItsOwnMapper() {
        AnnotatedDecisions camel = AnnotatedDecisions.using(CAMEL);
        AnnotatedDecision<Plain> camelDecision = camel.of(Plain.class);

        ObjectMapper snakeMapper = CAMEL.rebuild().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        AnnotatedDecisions snake = AnnotatedDecisions.using(snakeMapper);
        AnnotatedDecision<Plain> snakeDecision = snake.of(Plain.class);

        assertEquals(List.of("isUrgent", "department", "severityLevel"), names(camelDecision.spec()));
        assertEquals(List.of("is_urgent", "department", "severity_level"), names(snakeDecision.spec()));
        assertNotEquals(camelDecision.spec().getDefinitionId(), snakeDecision.spec().getDefinitionId());

        assertSame(camelDecision, camel.of(Plain.class));
        assertEquals(List.of("isUrgent", "department", "severityLevel"), names(camel.of(Plain.class).spec()));
        assertSame(snakeDecision, snake.of(Plain.class));
        assertSame(CAMEL, camel.mapper());
        assertSame(snakeMapper, snake.mapper());
    }

    @Test
    void defaultsIsOneInstance() {
        assertSame(AnnotatedDecisions.defaults(), AnnotatedDecisions.defaults());
        assertNotSame(AnnotatedDecisions.defaults(), AnnotatedDecisions.using(CAMEL));
    }
}
