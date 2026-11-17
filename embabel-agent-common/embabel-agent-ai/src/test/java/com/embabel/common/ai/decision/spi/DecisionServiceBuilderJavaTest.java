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
package com.embabel.common.ai.decision.spi;

import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.DecisionCall;
import com.embabel.common.ai.decision.DecisionCapabilities;
import com.embabel.common.ai.decision.DecisionOption;
import com.embabel.common.ai.decision.DecisionOptions;
import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.DecisionServiceBuilder;
import com.embabel.common.ai.decision.DecisionServiceMetadata;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.PropositionQuestionSpec;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.decision.QuestionKind;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.decision.TextDecisionInput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DecisionServiceBuilderJavaTest {

    private final ModelProvenance provenance = new ModelProvenance("model-a", "provider-a");

    private final PropositionQuestionSpec urgent = Questions.named("urgent").proposition("Is it urgent?").build();

    private final DecisionOption<String> effort = DecisionOption.of("acme", "effort", String.class);

    @Test
    void chainBuildsAServiceThatAnswersACallWithAnOption() {
        List<DecisionCall> seen = new ArrayList<>();
        DecisionExecutor executor = call -> {
            seen.add(call);
            return DecisionResponse.builder(call.getSpec())
                    .answer(urgent, new PropositionResult.Answered(true, provenance))
                    .build();
        };
        DecisionCapabilities capabilities = DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION))
                .acceptingOption(effort)
                .acceptingOption(DecisionOptions.TIMEOUT);

        DecisionService service = DecisionService.builder(
                        DecisionServiceMetadata.create("acme-decider", "acme"), capabilities, executor)
                .withDefaultOption(effort, "low")
                .withPreflight(call -> {
                    if ("forbidden".equals(call.option(effort))) {
                        throw new IllegalStateException("effort not allowed");
                    }
                })
                .build();

        DecisionCall call = DecisionCall.of(new TextDecisionInput("the printer is on fire"), DecisionSpec.of(urgent))
                .withOption(DecisionOptions.TIMEOUT, Duration.ofSeconds(30));
        PropositionResult answer = service.ask(call).answer(urgent);

        assertEquals(new PropositionResult.Answered(true, provenance), answer);
        assertEquals("low", seen.get(0).option(effort));
        assertEquals("acme-decider", service.getName());

        DecisionCall forbidden = call.withOption(effort, "forbidden");
        assertThrows(IllegalStateException.class, () -> service.ask(forbidden));
        assertEquals(1, seen.size());
    }
    /**
     * A service written against the base API that happens to declare its own nested types called
     * Builder and Scope. Configuring a service must not add member types with those names to the
     * interface, or this class would no longer see its own types the way it expects.
     */
    static final class ServiceWithItsOwnBuilderAndScope implements DecisionService {

        static final class Builder {
            String name() {
                return "own-builder";
            }
        }

        static final class Scope {
            String label() {
                return "own-scope";
            }
        }

        private final Builder builder = new Builder();

        private final Scope scope = new Scope();

        @Override
        public String getName() {
            return builder.name();
        }

        @Override
        public String getProvider() {
            return scope.label();
        }

        @Override
        public ClassificationResult classify(ClassificationRequest request) {
            return new ClassificationResult.NoMatch(new ModelProvenance("own", "test"));
        }

        @Override
        public PropositionResult assess(PropositionRequest request) {
            return new PropositionResult.Answered(true, new ModelProvenance("own", "test"));
        }

        DecisionServiceBuilder configure(DecisionExecutor executor) {
            return DecisionService.builder(
                    DecisionServiceMetadata.create(getName(), getProvider()),
                    DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION)),
                    executor);
        }
    }

    @Test
    void anImplementerWithItsOwnNestedBuilderAndScopeCompilesAndCanStillCallBuilder() {
        ServiceWithItsOwnBuilderAndScope own = new ServiceWithItsOwnBuilderAndScope();
        assertEquals("own-builder", own.getName());
        assertEquals("own-scope", own.getProvider());

        DecisionService built = own.configure(call -> DecisionResponse.builder(call.getSpec())
                        .answer(urgent, new PropositionResult.Answered(true, provenance))
                        .build())
                .build();
        assertNotNull(built);
        assertEquals("own-builder", built.getName());
        PropositionResult answer = built.ask("the printer is on fire", DecisionSpec.of(urgent)).answer(urgent);
        assertEquals(new PropositionResult.Answered(true, provenance), answer);
    }
}
