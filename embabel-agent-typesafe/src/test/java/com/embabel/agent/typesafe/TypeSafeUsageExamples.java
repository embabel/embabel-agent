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
package com.embabel.agent.typesafe;

import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.model.DecisionService;

import java.util.function.Supplier;

/** Compiled source for the provider-neutral TypeSafe reference examples. */
public class TypeSafeUsageExamples {

    // tag::service[]
    /**
     * Creates a Jev decision service with credentials resolved for each request.
     *
     * @param apiKey supplier of a nonblank API key
     * @return an observed provider-neutral decision service
     */
    public DecisionService decisionService(Supplier<String> apiKey) {
        return new TypeSafeModelFactory(apiKey).build();
    }
    // end::service[]

    // tag::byok[]
    /**
     * Validates a caller-supplied credential and returns a ready Jev decision service.
     *
     * @param apiKey caller-supplied credential
     * @return the default decision service after provider validation
     */
    public DecisionService validatedDecisionService(String apiKey) {
        return new TypeSafeModelFactory(() -> apiKey).buildValidated();
    }
    // end::byok[]

    // tag::model[]
    /**
     * Builds an independent service for an explicit model and assesses one proposition.
     *
     * @param factory shared TypeSafe provider configuration
     * @param model model identifier or alias
     * @param message state to assess
     * @return provider-neutral evidence with TypeSafe provenance
     */
    public PropositionResult assessWithModel(
            TypeSafeModelFactory factory, String model, String message) {
        return factory.build(model)
                .assess(
                        new PropositionRequest(
                                message, "This message requires urgent attention"));
    }
    // end::model[]
}
