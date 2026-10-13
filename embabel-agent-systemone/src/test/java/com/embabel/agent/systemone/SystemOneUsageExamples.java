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
package com.embabel.agent.systemone;

import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.DecisionSpec;

import java.net.URI;
import java.util.List;
import java.util.function.Supplier;

/** Compiled source for the System One reference examples. */
public class SystemOneUsageExamples {

    // tag::factory[]
    /**
     * Builds a decision service for one model on a System One endpoint and asks it a spec.
     *
     * @param apiKey supplies the key for each call
     * @param spec the questions to ask
     * @return one answer per question
     */
    public DecisionResponse ask(Supplier<String> apiKey, DecisionSpec spec) {
        var endpoint =
                new SystemOneEndpoint("Acme", URI.create("https://decisions.acme.example"));
        var factory = new SystemOneModelFactory(endpoint, "acme-small", apiKey::get);
        DecisionService service = factory.build();
        return service.ask("I was charged twice for one invoice.", spec);
    }
    // end::factory[]

    // tag::models[]
    /**
     * Lists the endpoint's models and builds a service for each.
     *
     * @param factory the endpoint's factory
     * @return one service per listed model
     */
    public List<DecisionService> everyModel(SystemOneModelFactory factory) {
        return factory.modelNames().stream().map(factory::build).toList();
    }
    // end::models[]

    // tag::byok[]
    /**
     * Checks a key supplied at run time and returns the default model.
     *
     * @param endpoint where the models live
     * @param apiKey the caller's key
     * @return the default model, once the endpoint accepts the key
     */
    public DecisionService validated(SystemOneEndpoint endpoint, String apiKey) {
        return new SystemOneModelFactory(endpoint, "acme-small", () -> apiKey).buildValidated();
    }
    // end::byok[]
}
