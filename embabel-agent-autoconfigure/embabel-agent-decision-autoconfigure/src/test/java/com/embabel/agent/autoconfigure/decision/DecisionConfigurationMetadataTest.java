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

import static org.assertj.core.api.Assertions.assertThat;

class DecisionConfigurationMetadataTest {
    @Test
    void metadataPublishesEveryPublicPropertyWithoutASecretValue() throws IOException {
        var stream = getClass().getClassLoader().getResourceAsStream("META-INF/spring-configuration-metadata.json");
        assertThat(stream).isNotNull();
        String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        var root = EmbabelObjectMapperHolder.createDefault().get().readTree(metadata);
        assertThat(root.get("properties")).hasSize(13);
        for (String suffix : new String[]{
                "enabled", "provider", "default-timeout", "record-mode", "full-record-max-bytes",
                "record-allowlist", "mapper-bean-name", "typesafe.model", "typesafe.base-url",
                "typesafe.connect-timeout", "typesafe.api-key", "prompted.llm-bean-name",
                "prompted.options-bean-name"
        }) {
            assertThat(metadata).contains("embabel.agent.decision." + suffix);
        }
        assertThat(metadata).contains("30s", "10s", "65536").doesNotContain("TYPESAFE_API_KEY=");
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
