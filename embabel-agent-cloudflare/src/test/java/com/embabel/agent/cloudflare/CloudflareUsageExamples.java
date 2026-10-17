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
package com.embabel.agent.cloudflare;

import com.embabel.common.ai.decision.DecisionResponse;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.DecisionSpec;

import java.util.function.Supplier;

/** Compiled source for the Cloudflare reference examples. */
public class CloudflareUsageExamples {

    // tag::service[]
    private final DecisionService clef;

    /**
     * Creates a reusable CLEF service for an account.
     *
     * @param accountId the Cloudflare account that runs the model
     * @param apiToken supplies a token with Workers AI access for each call
     */
    public CloudflareUsageExamples(String accountId, Supplier<String> apiToken) {
        var factory = new CloudflareModelFactory(accountId, apiToken::get);
        this.clef = factory.build();
    }

    /**
     * Asks a spec using the shared service.
     *
     * @param spec the questions to ask
     * @return one answer per question
     */
    public DecisionResponse ask(DecisionSpec spec) {
        return clef.ask("I was charged twice for one invoice.", spec);
    }
    // end::service[]

    // tag::flash[]
    /**
     * Builds a service for the smaller CLEF model.
     *
     * @param factory the account's factory
     * @return the CLEF Flash service
     */
    public DecisionService flash(CloudflareModelFactory factory) {
        return factory.build(CloudflareModelFactory.CLEF_FLASH);
    }
    // end::flash[]

    // tag::byok[]
    /**
     * Checks a token supplied at run time and returns CLEF.
     *
     * @param accountId the Cloudflare account that runs the model
     * @param apiToken the caller's token
     * @return CLEF, once Cloudflare accepts the token
     */
    public DecisionService validated(String accountId, String apiToken) {
        return new CloudflareModelFactory(accountId, () -> apiToken).buildValidated();
    }
    // end::byok[]
}
