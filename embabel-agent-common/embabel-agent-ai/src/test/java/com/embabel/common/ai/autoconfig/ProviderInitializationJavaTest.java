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
package com.embabel.common.ai.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class ProviderInitializationJavaTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void theFourArgumentConstructorRegistersNoDecisionModels() {
        var llms = List.of(new RegisteredModel("gpt4", "gpt-4"));
        var embeddings = List.of(new RegisteredModel("small", "text-embedding-3-small"));

        var result = new ProviderInitialization("OpenAI", llms, embeddings, AT);

        assertThat(result.getProvider()).isEqualTo("OpenAI");
        assertThat(result.getRegisteredLlms()).isEqualTo(llms);
        assertThat(result.getRegisteredEmbeddings()).isEqualTo(embeddings);
        assertThat(result.getInitializedAt()).isEqualTo(AT);
        assertThat(result.getRegisteredDecisionModels()).isEmpty();
    }

    @Test
    void theFiveArgumentConstructorTakesDecisionModels() {
        var decisions = List.of(new RegisteredModel("jev-latest", "jev-latest"));

        var result = new ProviderInitialization("TypeSafe", List.of(), List.of(), AT, decisions);

        assertThat(result.getRegisteredDecisionModels()).isEqualTo(decisions);
        assertThat(result.getTotalDecisionModels()).isEqualTo(1);
        assertThat(result.getInitializedAt()).isEqualTo(AT);
    }
}
