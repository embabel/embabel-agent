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
import com.embabel.common.ai.classification.ClassificationService;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.support.NoOpDecisionService;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.embabel.common.ai.model.DecisionModelProvider;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import com.embabel.common.ai.model.ModelType;
import com.embabel.common.ai.model.ServiceSelectionException;

import io.micrometer.observation.ObservationRegistry;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the registry of the decision services configuration example without Spring, and checks
 * that every name the example YAML binds resolves in it.
 */
class DecisionServicesPlainJavaExampleTest {
    static final String CHAT_MODEL = "gpt-4.1-mini";

    private final ChatModels chatModels = new ChatModels();

    @Test
    void plainJavaRegistryResolvesDefaultsRolesAndNames() {
        var observations = ObservationRegistry.create();
        var registry = registry(observations, chatModels);

        assertThat(registry.registrationNames()).containsExactly("jev-latest", "triage-stub", "disabled");
        assertThat(registry.decisions().defaultService()).isSameAs(registry.decisions().named("jev-latest"));
        assertThat(registry.classifications().defaultService()).isSameAs(registry.decisions().named("jev-latest"));
        assertThat(registry.decisions().defaultService().getName()).isEqualTo(TypeSafeModelFactory.DEFAULT_MODEL);
        assertThat(registry.decisions().defaultService().getProvider()).isEqualTo(TypeSafeModelFactory.PROVIDER);
        assertThat(registry.decisions().byRole("support-triage").getName()).isEqualTo("jev-latest");
        assertThat(registry.decisions().byRole("tests").getName()).isEqualTo("triage-stub");
        assertThat(registry.decisions().named("disabled")).isInstanceOf(NoOpDecisionService.class);

        ClassificationService revision = registry.classifications().byRole("dice-revision");
        assertThat(revision.getType()).isEqualTo(ModelType.DECISION);
        assertThat(registry.getObservationRegistry()).isSameAs(observations);

        DecisionService perUser = StubDecisionService.builder("per-user").build();
        assertThat(registry.decisions().using(perUser)).isSameAs(perUser);
        assertThatThrownBy(() -> registry.decisions().byRole("ticket-routing"))
                .isInstanceOfSatisfying(
                        ServiceSelectionException.class,
                        e -> assertThat(e.getReason()).isEqualTo(ServiceSelectionException.Reason.UNKNOWN_ROLE));
    }

    @Test
    void chatModelRoleResolvesThroughTheProviderOnceForBothFamilies() {
        var registry = registry(ObservationRegistry.NOOP, chatModels);
        assertThat(chatModels.created).hasValue(0);

        var review = registry.decisions().byRole("dice-revision-review");

        assertThat(review.getName()).isEqualTo(CHAT_MODEL);
        assertThat(registry.decisions().named(CHAT_MODEL)).isSameAs(review);
        assertThat(registry.classifications().byRole("ticket-routing")).isSameAs(review);
        assertThat(registry.registrationNames()).doesNotContain(CHAT_MODEL);
        assertThat(chatModels.created).hasValue(1);
    }

    @Test
    void providerModelsAreNeverAnImplicitDefault() {
        var registry = DecisionServiceRegistry.builder().provider(chatModels).build();

        assertThat(registry.decisions().named(CHAT_MODEL).getName()).isEqualTo(CHAT_MODEL);
        assertThatThrownBy(() -> registry.decisions().defaultService())
                .isInstanceOfSatisfying(
                        ServiceSelectionException.class,
                        e -> assertThat(e.getReason()).isEqualTo(ServiceSelectionException.Reason.NO_DEFAULT));
    }

    @Test
    void roleNamingNeitherAServiceNorAProviderModelFailsTheBuild() {
        var builder = DecisionServiceRegistry.builder()
                .provider(chatModels)
                .decisionRole("review", "gpt-missing");

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embabel.models.decision.roles.review")
                .hasMessageContaining(CHAT_MODEL);
    }

    @Test
    void exampleYamlNamesOnlyServicesAndModelsTheRegistryResolves() throws IOException {
        var sources = new YamlPropertySourceLoader()
                .load("decision-services-example", new ClassPathResource("decision-services-example.yml"));
        var binder = new Binder(ConfigurationPropertySources.from(sources));
        Bindable<Map<String, String>> stringMap = Bindable.mapOf(String.class, String.class);

        assertThat(binder.bind("embabel.agent.platform.models.typesafe.api-key", String.class).get())
                .isEqualTo("${TYPESAFE_API_KEY}");
        var decisionDefault = binder.bind("embabel.models.decision.default", String.class).get();
        var decisionRoles = binder.bind("embabel.models.decision.roles", stringMap).get();
        var classificationRoles = binder.bind("embabel.models.classification.roles", stringMap).get();
        assertThat(decisionDefault).isEqualTo("jev-latest");
        assertThat(decisionRoles)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("support-triage", "jev-latest", "dice-revision-review", CHAT_MODEL));
        assertThat(classificationRoles)
                .containsExactlyInAnyOrderEntriesOf(Map.of("dice-revision", CHAT_MODEL, "ticket-routing", CHAT_MODEL));

        var registry = registry(ObservationRegistry.NOOP, chatModels);
        assertThat(registry.decisions().named(decisionDefault)).isSameAs(registry.decisions().defaultService());
        decisionRoles.values().forEach(name -> assertThat(registry.decisions().named(name)).isNotNull());
        classificationRoles.values().forEach(name -> assertThat(registry.classifications().named(name)).isNotNull());
    }

    private static DecisionServiceRegistry registry(
            ObservationRegistry observations, DecisionModelProvider chatModels) {
        // tag::plain-java[]
        // Every Jev service built by one factory shares its credential, transport and observations.
        var jevFactory = new TypeSafeModelFactory(
                TypeSafeClientOptions.defaults(),
                () -> System.getenv("TYPESAFE_API_KEY"),
                observations);

        return DecisionServiceRegistry.builder()
                .register("jev-latest", jevFactory.build())
                .register("triage-stub", StubDecisionService.builder("triage-stub").build())
                .register("disabled", new NoOpDecisionService("disabled"))
                .defaultCandidate("jev-latest")
                .decisionRole("support-triage", "jev-latest")
                .decisionRole("tests", "triage-stub")
                // chatModels is a DecisionModelProvider, such as the LlmDecisionServiceFactory bean.
                // A name no registered service holds is built by the provider on first lookup.
                .provider(chatModels)
                .decisionRole("dice-revision-review", "gpt-4.1-mini")
                .classificationRole("dice-revision", "gpt-4.1-mini")
                .classificationRole("ticket-routing", "gpt-4.1-mini")
                // Pass the observation registry the services observe with.
                .observationRegistry(observations)
                .build();
        // end::plain-java[]
    }

    /** Serves one chat model with a stub, counting the services it builds. */
    static final class ChatModels implements DecisionModelProvider {
        final AtomicInteger created = new AtomicInteger();

        @Override
        public @NotNull String getProvider() {
            return "chat";
        }

        @Override
        public @NotNull List<String> modelNames() {
            return List.of(CHAT_MODEL);
        }

        @Override
        public DecisionService decisionServiceNamed(@NotNull String name) {
            if (!CHAT_MODEL.equals(name)) {
                return null;
            }
            created.incrementAndGet();
            return StubDecisionService.builder(name).build();
        }
    }
}
