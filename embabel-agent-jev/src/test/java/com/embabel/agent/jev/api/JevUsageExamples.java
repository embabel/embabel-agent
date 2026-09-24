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
package com.embabel.agent.jev.api;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Compiled source for the Jev reference examples. Calls remain synchronous and return native SDK
 * responses so application code can retain the evidence it needs.
 */
public class JevUsageExamples {

    // tag::client[]
    /**
     * Creates a client with default options and credentials resolved for each request.
     *
     * @param apiKey supplier of a nonblank API key
     * @return a native client with Embabel's guarded fallback transport
     */
    public TypeSafeClient client(Supplier<String> apiKey) {
        return JevClients.create(JevClientOptions.defaults(), apiKey);
    }

    // end::client[]

    // tag::evidence[]
    /**
     * Keeps the reported mean and distribution separate from application selection policy.
     *
     * @param reportedMean the score reported by the service
     * @param distribution probabilities for each rubric level
     */
    public record SeverityEvidence(double reportedMean, Map<Integer, Double> distribution) {}

    /**
     * Extracts the priority score without rounding or choosing a rubric level.
     *
     * @param response native response containing a score named {@code priority}
     * @return both pieces of score evidence
     */
    public SeverityEvidence severity(SystemOneResponse response) {
        var score = response.score("priority");
        return new SeverityEvidence(score.value(), score.probabilities());
    }

    // end::evidence[]

    // tag::model[]
    /**
     * Selects an explicit model for one synchronous request.
     *
     * @param client native Jev client
     * @param model model identifier for this request
     * @param message state to assess
     * @return the native response containing the named urgency answer
     */
    public SystemOneResponse assessWithModel(TypeSafeClient client, String model, String message) {
        var request =
                SystemOneRequest.builder()
                        .state(message)
                        .model(model)
                        .question("urgent", Noul.of("Does this message require urgent attention?"))
                        .build();
        return client.systemOne(request);
    }
    // end::model[]
}
