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

import java.util.Map;
import java.util.function.Supplier;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.SystemOneResponse;

/** Compiled source for the Jev reference examples. */
public class JevUsageExamples {

    // tag::client[]
    public TypeSafeClient client(Supplier<String> apiKey) {
        return JevClients.create(JevClientOptions.defaults(), apiKey);
    }
    // end::client[]

    // tag::evidence[]
    public record SeverityEvidence(double reportedMean, Map<Integer, Double> distribution) {
    }

    public SeverityEvidence severity(SystemOneResponse response) {
        var score = response.score("priority");
        return new SeverityEvidence(score.value(), score.probabilities());
    }
    // end::evidence[]

    // tag::model[]
    public SystemOneResponse assessWithModel(TypeSafeClient client, String model, String message) {
        var request = SystemOneRequest.builder()
                .state(message)
                .model(model)
                .question("urgent", Noul.of("Does this message require urgent attention?"))
                .build();
        return client.systemOne(request);
    }
    // end::model[]
}
