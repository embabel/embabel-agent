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
package com.embabel.common.ai.decision.json;

import static org.junit.jupiter.api.Assertions.*;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.ExecutionMode;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.RatingQuestionSpec;
import com.embabel.common.ai.decision.RatingResult;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

class DecisionJsonJavaTest {

    private static final ModelProvenance JEV = new ModelProvenance("jev-latest", "typesafe");

    private final PropositionQuestionSpec urgent = Questions.named("is_urgent")
        .proposition("Does this convey urgency?")
        .build();

    private final ChoiceQuestionSpec department = Questions.named("department")
        .choice("Which team should handle this?")
        .option("billing", "Payments, invoicing, refunds")
        .option("technical", "Bugs, outages, integrations")
        .option("sales", "Pricing, upgrades, new accounts")
        .build();

    private final RatingQuestionSpec frustration = Questions.named("frustration")
        .rating("How frustrated is the customer?")
        .level("Calm")
        .level("Frustrated")
        .level("Very angry")
        .build();

    private final DecisionSpec spec = DecisionSpec.of(urgent, department, frustration);

    private DecisionResponse answered() {
        return DecisionResponse.builder(spec, ExecutionMode.NATIVE)
            .answer(urgent, new PropositionResult.Answered(true, JEV, 0.93))
            .answer(department, new ClassificationResult.Selected("billing", JEV, 0.91))
            .answer(frustration, new RatingResult.Answered(JEV, "Frustrated"))
            .build();
    }

    @Test
    void aPlainMapperWithTheModuleRoundTripsSpecAndResponse() {
        var response = answered();

        // tag::jackson[]
        JsonMapper mapper = JsonMapper.builder()
            .addModule(new DecisionJacksonModule())
            .build();

        String specJson = mapper.writeValueAsString(spec);
        DecisionSpec specRead = mapper.readValue(specJson, DecisionSpec.class);

        String responseJson = mapper.writeValueAsString(response);
        DecisionResponse responseRead = mapper.readValue(responseJson, DecisionResponse.class);
        ClassificationResult team = responseRead.answer(department);
        // end::jackson[]

        assertEquals(spec, specRead);
        assertEquals(spec.getDefinitionId(), specRead.getDefinitionId());
        assertEquals(response, responseRead);
        assertEquals(new ClassificationResult.Selected("billing", JEV, 0.91), team);
        assertFalse(responseJson.contains("instructions"));
    }

    @Test
    void aResponseReadWithoutTheSpecWorksWithAnEquivalentQuestion() {
        JsonMapper mapper = JsonMapper.builder().addModule(new DecisionJacksonModule()).build();
        String json = mapper.writeValueAsString(answered());

        DecisionResponse read = JsonMapper.builder().findAndAddModules().build().readValue(json, DecisionResponse.class);
        RatingQuestionSpec rebuilt = Questions.named("frustration")
            .rating("How frustrated is the customer?")
            .level("Calm")
            .level("Frustrated")
            .level("Very angry")
            .build();
        RatingResult anger = read.answer(rebuilt);
        assertEquals("Frustrated", assertInstanceOf(RatingResult.Answered.class, anger).getSelectedLevelId());
    }

    @Test
    void aResponseWithADroppedAnswerIsRejected() {
        JsonMapper mapper = JsonMapper.builder().addModule(new DecisionJacksonModule()).build();
        String json = mapper.writeValueAsString(answered());
        int start = json.indexOf(",\"frustration\":");
        String dropped = json.substring(0, start) + "}}";

        var error = assertThrows(MismatchedInputException.class, () -> mapper.readValue(dropped, DecisionResponse.class));
        assertTrue(error.getMessage().contains("The answers do not match the response's spec"), error.getMessage());
    }
}
