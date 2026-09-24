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

import com.embabel.common.util.EmbabelObjectMapperHolder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionConfigurationMetadataTest {
    @Test
    void metadataPublishesTheNamedMapContractWithoutLegacyOrSecretKeys() throws IOException {
        var stream = getClass().getClassLoader().getResourceAsStream("META-INF/spring-configuration-metadata.json");
        assertThat(stream).isNotNull();
        String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        var root = EmbabelObjectMapperHolder.createDefault().get().readTree(metadata);
        var names = new ArrayList<String>();
        root.get("properties").forEach(property -> names.add(property.get("name").stringValue()));
        for (String suffix : new String[]{
                "enabled", "default-timeout", "record-mode", "full-record-max-bytes",
                "record-allowlist", "mapper-bean-name", "models"
        }) {
            assertThat(names).contains("embabel.agent.platform.decision." + suffix);
        }
        assertThat(names).noneMatch(name -> name.contains("*"));
        var hintNames = new ArrayList<String>();
        root.get("hints").forEach(hint -> hintNames.add(hint.get("name").stringValue()));
        assertThat(hintNames).noneMatch(name -> name.contains("*"));
        assertThat(names).doesNotContain(
                "embabel.agent.platform.decision.provider",
                "embabel.agent.platform.decision.typesafe.model",
                "embabel.agent.platform.decision.prompted.llm-bean-name");
        assertThat(metadata)
                .contains("30s", "65536", "proposition-revision", "Spring bean and registry name",
                        "backend requested model")
                .doesNotContain("TYPESAFE_API_KEY=", "\"value\" : \"stub\"");
    }

    @Test
    void nestedModelRecordsBindFromMapValuesWithProviderDefaults() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "models.review.provider", "typesafe",
                "models.review.typesafe.model", "jev-latest",
                "models.prompted.provider", "prompted",
                "models.prompted.prompted.llm-bean-name", "reviewLlm")));
        var models = binder.bind("models", Bindable.mapOf(String.class, DecisionProperties.Model.class)).get();
        assertThat(models.get("review").provider()).isEqualTo("typesafe");
        assertThat(models.get("review").typesafe().model()).isEqualTo("jev-latest");
        assertThat(models.get("review").typesafe().baseUrl()).isEqualTo(URI.create("https://api.typesafe.ai"));
        assertThat(models.get("review").typesafe().connectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(models.get("prompted").prompted().llmBeanName()).isEqualTo("reviewLlm");
        assertThat(models.get("prompted").prompted().optionsBeanName()).isNull();
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
