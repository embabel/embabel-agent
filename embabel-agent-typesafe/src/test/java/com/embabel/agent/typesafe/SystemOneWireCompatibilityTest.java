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

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.assertThat;

class SystemOneWireCompatibilityTest {
    // Exact fixture bytes emitted by pure OpenJev75f22b6dad8c360fdba0e0ebd3dc0a1187628f60
    // engine.py confidence/to_answer (SHA256 e084734f969b414be701bc438617c302e0b44867d907956ec87dcafc66b1d089).
    // TypeSafe OpenAPI0.2.0 SHA256 a191f8a7df6bd6fedced8120dd0fd106f88575d1d1c8360d08900a6c7c0360d5.
    // Independent community SDK decoder evidence; no live backend/model/tokenizer compatibility claim.
    private static final String FIXTURE = """
            {
              "model": "openjev-fixture",
              "answers": {
                "q001": {
                  "type": "noul",
                  "noul": 0.6
                },
                "q002": {
                  "type": "choice",
                  "choice": "one",
                  "probabilities": {
                    "one": 0.6,
                    "two": 0.4
                  },
                  "confidence": 0.029049405545331197
                },
                "q003": {
                  "type": "score",
                  "score": 0.75,
                  "legend": {
                    "0": "Low",
                    "1": "High"
                  },
                  "probabilities": {
                    "0": 0.25,
                    "1": 0.75
                  },
                  "confidence": 0.18872187554086717
                }
              },
              "usage": {
                "input_tokens": 100,
                "output_tokens": 0
              }
            }
            """;

    @Test
    void publishedSdkDecodesIdenticalPinnedCodecBytes() throws Exception {
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(FIXTURE.getBytes(StandardCharsets.UTF_8)))).isEqualTo("39ecce5e867178f669fad4cb53ed1aab8972cdfb572fb777b2066cb4cc9883ab");
        var response = JsonMapper.builder().build().readValue(FIXTURE, SystemOneResponse.class);
        assertThat(response.model()).isEqualTo("openjev-fixture");
        assertThat(response.noulValue("q001")).isEqualTo(0.6);
        assertThat(response.choice("q002").value()).isEqualTo("one");
        assertThat(response.choice("q002").confidence()).isEqualTo(0.029049405545331197);
        assertThat(response.choice("q002").probabilities()).containsEntry("one", 0.6).containsEntry("two", 0.4);
        assertThat(response.score("q003").value()).isEqualTo(0.75);
        assertThat(response.score("q003").confidence()).isEqualTo(0.18872187554086717);
        assertThat(response.score("q003").probabilities()).containsEntry(0, 0.25).containsEntry(1, 0.75);
        assertThat(response.usage().inputTokens()).isEqualTo(100);
    }
}
