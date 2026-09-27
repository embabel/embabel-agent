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
package com.embabel.agent.typesafe.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.embabel.agent.typesafe.TypeSafeClientOptions;
import com.embabel.agent.typesafe.TypeSafeModelFactory;
import com.embabel.common.ai.decision.support.NoOpDecisionService;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.embabel.common.ai.model.ClassificationService;
import com.embabel.common.ai.model.DecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import com.embabel.common.ai.model.ModelType;
import com.embabel.common.ai.model.ServiceSelectionException;

import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

/**
 * Builds the registry of the decision services configuration example without Spring, and checks
 * that the example YAML binds the same registration names and roles.
 */
class DecisionServicesPlainJavaExampleTest {

    @Test
    void plainJavaRegistryResolvesDefaultsRolesAndNames() {
        ObservationRegistry observations = ObservationRegistry.create();
        DecisionServiceRegistry registry = registry(observations);

        assertThat(registry.registrationNames())
                .containsExactly("typeSafeDecisionService", "jev", "jev-fast", "triage-stub", "disabled");
        assertThat(registry.decisions().defaultService().getName()).isEqualTo("jev-latest");
        assertThat(registry.decisions().byRole("support-triage").getName()).isEqualTo("jev-latest");
        assertThat(registry.decisions().named("jev-fast").getName()).isEqualTo("jev-fast");
        assertThat(registry.decisions().named("jev").getProvider()).isEqualTo(TypeSafeModelFactory.PROVIDER);
        assertThat(registry.decisions().byRole("tests").getName()).isEqualTo("triage-stub");
        assertThat(registry.decisions().named("disabled")).isInstanceOf(NoOpDecisionService.class);

        ClassificationService revision = registry.classifications().byRole("dice-revision");
        assertThat(revision.getType()).isEqualTo(ModelType.DECISION);
        assertThat(registry.getObservationRegistry()).isSameAs(observations);

        DecisionService perUser = StubDecisionService.builder("per-user").build();
        assertThat(registry.decisions().using(perUser)).isSameAs(perUser);
        assertThatThrownBy(() -> registry.decisions().byRole("dice-revision-review"))
                .isInstanceOfSatisfying(
                        ServiceSelectionException.class,
                        e -> assertThat(e.getReason()).isEqualTo(ServiceSelectionException.Reason.UNKNOWN_ROLE));
    }

    @Test
    void exampleYamlBindsTheJevServicesAndRoles() throws IOException {
        var sources = new YamlPropertySourceLoader()
                .load("decision-services-example", new ClassPathResource("decision-services-example.yml"));
        var binder = new Binder(ConfigurationPropertySources.from(sources));
        Bindable<Map<String, String>> stringMap = Bindable.mapOf(String.class, String.class);

        assertThat(binder.bind("embabel.agent.platform.models.typesafe.services.jev.model", String.class).get())
                .isEqualTo("jev-latest");
        assertThat(binder.bind("embabel.agent.platform.models.typesafe.services.jev-fast.model", String.class).get())
                .isEqualTo("jev-fast");
        assertThat(binder.bind("embabel.agent.platform.decisions.llm.services.llm-review.llm", String.class).get())
                .isNotBlank();
        assertThat(binder.bind("embabel.models.decision.roles", stringMap).get())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("support-triage", "jev", "dice-revision-review", "llm-review"));
        assertThat(binder.bind("embabel.models.classification.roles", stringMap).get())
                .containsExactlyInAnyOrderEntriesOf(Map.of("dice-revision", "llm-review"));
        assertThat(binder.bind("embabel.models.decision.default", String.class).isBound()).isFalse();

        var registered = registry(ObservationRegistry.NOOP).registrationNames();
        assertThat(registered).contains("jev", "jev-fast", "typeSafeDecisionService");
    }

    private static DecisionServiceRegistry registry(ObservationRegistry observations) {
        // tag::plain-java[]
        // Every Jev service built by one factory shares its credential, transport and observations.
        var jevFactory = new TypeSafeModelFactory(
                TypeSafeClientOptions.defaults(),
                () -> System.getenv("TYPESAFE_API_KEY"),
                observations);

        // Plain Java cannot build a prompted LLM service: it needs the platform's LLM operations.
        return DecisionServiceRegistry.builder()
                .register("typeSafeDecisionService", jevFactory.build())
                .register("jev", jevFactory.build("jev-latest"))
                .register("jev-fast", jevFactory.build("jev-fast"))
                .register("triage-stub", StubDecisionService.builder("triage-stub").build())
                .register("disabled", new NoOpDecisionService("disabled"))
                .defaultCandidate("typeSafeDecisionService")
                .decisionRole("support-triage", "jev")
                .decisionRole("tests", "triage-stub")
                .classificationRole("dice-revision", "jev")
                // Pass the observation registry the services observe with.
                .observationRegistry(observations)
                .build();
        // end::plain-java[]
    }
}
