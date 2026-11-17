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
package com.embabel.agent.openai.decision;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionCall;
import com.embabel.common.ai.decision.DecisionCapabilities;
import com.embabel.common.ai.decision.DecisionInput;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.DecisionServiceMetadata;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.DecisionUsage;
import com.embabel.common.ai.decision.JsonDecisionInput;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.Question;
import com.embabel.common.ai.decision.QuestionKind;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.TextDecisionInput;
import com.embabel.common.ai.decision.UnsupportedDecisionException;
import com.embabel.common.ai.decision.spi.DecisionExecutor;
import com.embabel.common.ai.decision.spi.DecisionResponseAssembler;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java examples on the decision providers reference page. Each tagged region is included in
 * the page as it stands, and the tests around it check that it does what the page says.
 */
class DecisionProviderJavaExamplesTest {

    private final PropositionQuestionSpec refund =
            Questions.named("refund").proposition("Does the customer ask for a refund?").build();

    private final ChoiceQuestionSpec route = Questions.named("route").choice("Which team should handle this ticket?")
            .option("billing", "Payment problems")
            .option("support", "Other problems")
            .build();

    private final ModelProvenance modelProvenance = new ModelProvenance("ticket-model", "example");

    private DecisionService rulesService() {
        // tag::rules-provider-java[]
        DecisionExecutor rules = call -> {
            String text = ((TextDecisionInput) call.getInput()).getValue();
            Question<?> question = call.getSpec().getQuestions().get(0);
            ModelProvenance provenance = new ModelProvenance("refund-rules-v1", "in-process");
            DecisionResponseAssembler answers = DecisionResponseAssembler.forSpec(call.getSpec(), "refund-rules");
            if (question instanceof ChoiceQuestionSpec) {
                String team = text.contains("charged") ? "billing" : "support";
                answers.choice(question.getName(), new ClassificationResult.Selected(team, provenance));
            } else {
                answers.proposition(question.getName(), new PropositionResult.Answered(text.contains("refund"), provenance));
            }
            return answers.build();
        };
        DecisionCapabilities capabilities = DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.CHOICE))
                .withMaxQuestions(1);
        DecisionService service = DecisionService.from(
                DecisionServiceMetadata.create("refund-rules", "in-process"), capabilities, rules);
        // end::rules-provider-java[]
        return service;
    }

    private DecisionService ticketService(List<DecisionCall> seen) {
        DecisionCapabilities capabilities = DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.CHOICE))
                .acceptingInput(DecisionInput.JSON)
                .withMaxQuestions(400)
                .acceptingOption(OpenAiDecisionOptions.PROMPT_CACHE_KEY)
                .acceptingOption(OpenAiDecisionOptions.SAFETY_IDENTIFIER);
        DecisionExecutor executor = call -> {
            seen.add(call);
            return DecisionResponseAssembler.forSpec(call.getSpec(), "ticket-model")
                    .proposition("duplicate", new PropositionResult.Answered(true, modelProvenance, 0.93))
                    .refused("team", modelProvenance)
                    .usage(new DecisionUsage(812L, 9L))
                    .build();
        };
        return DecisionService.from(DecisionServiceMetadata.create("ticket-model", "example"), capabilities, executor);
    }

    @Test
    void rulesProviderAnswersOnePropositionFromText() {
        DecisionResponse response = rulesService().ask("I was charged twice and want a refund.", DecisionSpec.of(refund));

        assertEquals(
                new PropositionResult.Answered(true, new ModelProvenance("refund-rules-v1", "in-process")),
                response.answer(refund));
    }

    @Test
    void rulesProviderAnswersOneChoiceFromText() {
        ClassificationResult outcome = rulesService().ask("I was charged twice.", DecisionSpec.of(route)).answer(route);

        assertEquals("billing", assertInstanceOf(ClassificationResult.Selected.class, outcome).getCategoryId());
    }

    @Test
    void specOverTheQuestionLimitIsRejectedWhole() {
        DecisionService service = rulesService();

        assertThrows(UnsupportedDecisionException.class,
                () -> service.ask("I was charged twice.", DecisionSpec.of(refund, route)));
    }

    @Test
    void structuredCallReachesTheProviderAsJsonWithItsOption() {
        List<DecisionCall> seen = new ArrayList<>();
        DecisionService service = ticketService(seen);

        // tag::structured-call-java[]
        JsonNode ticket = JsonMapper.builder().build().readTree("""
                {"order":{"id":"A-17","charges":[19.99,19.99]},"message":"I was charged twice."}""");
        PropositionQuestionSpec duplicate = Questions.named("duplicate").proposition("Was the order charged twice?").build();
        ChoiceQuestionSpec team = Questions.named("team").choice("Which team should handle this ticket?")
                .option("billing", "Payment problems")
                .option("support", "Other problems")
                .build();
        DecisionCall call = DecisionCall.of(new JsonDecisionInput(ticket), DecisionSpec.of(duplicate, team))
                .requiringNativeJson()
                .withOption(OpenAiDecisionOptions.PROMPT_CACHE_KEY, "support-triage-v1");
        DecisionResponse response = service.ask(call);
        PropositionResult charged = response.answer(duplicate);
        boolean teamRefused = response.wasRefused(team);
        DecisionUsage usage = response.getUsage();
        // end::structured-call-java[]

        DecisionCall received = seen.get(0);
        assertEquals(new JsonDecisionInput(ticket), received.getInput());
        assertTrue(received.getNativeJsonRequired());
        assertEquals("support-triage-v1", received.option(OpenAiDecisionOptions.PROMPT_CACHE_KEY));

        assertEquals(new PropositionResult.Answered(true, modelProvenance, 0.93), charged);
        assertTrue(teamRefused);
        assertInstanceOf(ClassificationResult.Inconclusive.class, response.answer(team));
        assertEquals(Set.of("team"), response.getRefusedQuestions());
        assertEquals(812L, usage.getInputTokens());
    }

    @Test
    void textInputCannotRequireNativeJson() {
        DecisionCall call = DecisionCall.of(new TextDecisionInput("refund please"), DecisionSpec.of(refund));

        assertThrows(IllegalArgumentException.class, call::requiringNativeJson);
    }
}
