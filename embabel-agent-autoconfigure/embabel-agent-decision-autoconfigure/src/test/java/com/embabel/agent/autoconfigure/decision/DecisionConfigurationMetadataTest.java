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
package com.embabel.agent.autoconfigure.decision;

import org.junit.jupiter.api.Test;
import com.embabel.common.util.EmbabelObjectMapperHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionConfigurationMetadataTest {
    @Test
    void metadataPublishesTheNamedMapContractWithoutLegacyOrSecretKeys() throws IOException {
        var stream = getClass().getClassLoader().getResourceAsStream("META-INF/spring-configuration-metadata.json");
        assertThat(stream).isNotNull();
        String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        var root = EmbabelObjectMapperHolder.createDefault().get().readTree(metadata);
        var names = new ArrayList<String>();
        root.get("properties").forEach(property -> names.add(property.get("name").asText()));
        for (String suffix : new String[]{
                "enabled", "default-timeout", "record-mode", "full-record-max-bytes",
                "record-allowlist", "mapper-bean-name", "models",
                "models.*.provider", "models.*.typesafe.model", "models.*.typesafe.base-url",
                "models.*.typesafe.connect-timeout", "models.*.prompted.llm-bean-name",
                "models.*.prompted.options-bean-name"
        }) {
            assertThat(names).contains("embabel.agent.decision." + suffix);
        }
        assertThat(names).doesNotContain(
                "embabel.agent.decision.provider",
                "embabel.agent.decision.typesafe.model",
                "embabel.agent.decision.prompted.llm-bean-name");
        assertThat(metadata)
                .contains("30s", "10s", "65536", "proposition-revision", "Spring bean and registry name",
                        "backend requested model")
                .doesNotContain("TYPESAFE_API_KEY=", "\"value\" : \"stub\"");
    }

    @Test
    void importsListsTheEntryPointExactlyOnce() throws IOException {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(resource).isNotNull();
        String imports = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(imports.lines().filter(line -> line.equals(AgentDecisionAutoConfiguration.class.getName())).count())
                .isEqualTo(1);
    }
}
