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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.embabel.common.ai.classification.Category;
import com.embabel.common.ai.classification.ClassificationRequest;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.ai.model.ModelType;
import com.embabel.common.byok.ByokFactory;
import com.embabel.common.byok.InvalidApiKeyException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

class TypeSafeModelFactoryTest {
    private static final String SYSTEM_ONE_URI = "https://api.typesafe.ai/v1/systemone";
    private static final String MODELS_URI = "https://api.typesafe.ai/v1/models";
    private static final ClassificationRequest CLASSIFICATION_REQUEST =
            new ClassificationRequest(
                    "A border collie is waiting by the door",
                    List.of(
                            new Category("dog", "A domestic dog"),
                            new Category("cat", "A domestic cat")));

    @Test
    void oneFactoryBuildsIndependentDecisionModels() {
        var factory = new TypeSafeModelFactory(() -> "test-key");

        var first = factory.build("jev-one");
        var second = factory.build("jev-two");

        assertThat(first.getName()).isEqualTo("jev-one");
        assertThat(second.getName()).isEqualTo("jev-two");
        assertThat(first.getProvider()).isEqualTo(TypeSafeModelFactory.PROVIDER);
        assertThat(first.getType()).isEqualTo(ModelType.DECISION);
        assertThat(factory).isInstanceOf(ByokFactory.class);
    }

    @Test
    void byokValidationReturnsTheConfiguredDefaultModel() {
        var fixture = fixture("jev-private");
        fixture.server()
                .expect(requestTo(MODELS_URI))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(
                        withSuccess(
                                """
                                {"models":[{"name":"jev-private"}]}
                                """,
                                MediaType.APPLICATION_JSON));

        assertThat(fixture.factory().buildValidated().getName()).isEqualTo("jev-private");
        fixture.server().verify();
    }

    @Test
    void byokValidationSanitizesProviderFailures() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(MODELS_URI))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private provider detail"));

        assertThatThrownBy(fixture.factory()::buildValidated)
                .isInstanceOf(InvalidApiKeyException.class)
                .hasMessage("TypeSafe credential could not be validated")
                .hasMessageNotContaining("private provider detail");
        fixture.server().verify();
    }

    @Test
    void classificationUsesClosedRequestCategoriesAndPreservesProvenance() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andExpect(jsonPath("$.model").value("configured-alias"))
                .andExpect(jsonPath("$.questions.classification.criteria.dog").exists())
                .andExpect(jsonPath("$.questions.classification.criteria.cat").exists())
                .andRespond(
                        withSuccess(
                                        """
                                        {"model":"jev-2026-09","answers":{"classification":{"type":"choice","choice":"dog","probabilities":{"dog":0.9,"cat":0.1},"confidence":0.8}}}
                                        """,
                                        MediaType.APPLICATION_JSON)
                                .header("x-typesafe-request-id", "request-17"));

        var result = fixture.factory().build("configured-alias").classify(CLASSIFICATION_REQUEST);

        assertThat(result).isInstanceOfSatisfying(
                ClassificationResult.Selected.class,
                selected -> {
                    assertThat(selected.getCategoryId()).isEqualTo("dog");
                    assertThat(selected.getConfidence()).isEqualTo(0.8);
                    assertThat(selected.getProvenance().getModelName()).isEqualTo("jev-2026-09");
                    assertThat(selected.getProvenance().getProvider())
                            .isEqualTo(TypeSafeModelFactory.PROVIDER);
                    assertThat(selected.getProvenance().getRequestId()).isEqualTo("request-17");
                });
        fixture.server().verify();
    }

    @Test
    void flatChoiceIsInconclusiveRatherThanAnArbitrarySelection() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(
                        withSuccess(
                                """
                                {"model":"jev-latest","answers":{"classification":{"type":"choice","choice":"dog","probabilities":{"dog":0.5,"cat":0.5},"confidence":0.0}}}
                                """,
                                MediaType.APPLICATION_JSON));

        assertThat(fixture.factory().build().classify(CLASSIFICATION_REQUEST))
                .isInstanceOf(ClassificationResult.Inconclusive.class);
        fixture.server().verify();
    }

    @Test
    void invalidChoiceSupportIsAnInvalidResponse() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(
                        withSuccess(
                                """
                                {"answers":{"classification":{"type":"choice","choice":"dog","probabilities":{"dog":1.0},"confidence":1.0}}}
                                """,
                                MediaType.APPLICATION_JSON));

        assertThat(fixture.factory().build().classify(CLASSIFICATION_REQUEST))
                .isEqualTo(new ClassificationResult.Failure(FailureReason.INVALID_RESPONSE));
        fixture.server().verify();
    }

    @Test
    void propositionKeepsFalseUndecidedAndTrueDistinct() {
        var fixture = fixture();
        for (var probability : new double[] {0.2d, 0.5d, 0.8d}) {
            fixture.server()
                    .expect(requestTo(SYSTEM_ONE_URI))
                    .andRespond(
                            withSuccess(
                                    """
                                    {"model":"jev-latest","answers":{"proposition":{"type":"noul","noul":%s}}}
                                    """
                                            .formatted(probability),
                                    MediaType.APPLICATION_JSON));
        }
        var service = fixture.factory().build();
        var request = new PropositionRequest("Evidence", "The proposition is supported");

        assertThat(service.assess(request))
                .isInstanceOfSatisfying(
                        PropositionResult.Answered.class,
                        answered -> {
                            assertThat(answered.getAnswer()).isFalse();
                            assertThat(answered.getPTrue()).isEqualTo(0.2d);
                        });
        assertThat(service.assess(request)).isInstanceOf(PropositionResult.Inconclusive.class);
        assertThat(service.assess(request))
                .isInstanceOfSatisfying(
                        PropositionResult.Answered.class,
                        answered -> {
                            assertThat(answered.getAnswer()).isTrue();
                            assertThat(answered.getPTrue()).isEqualTo(0.8d);
                        });
        fixture.server().verify();
    }

    @Test
    void providerAndResponseFailuresRemainDistinctAndSanitized() {
        var fixture = fixture();
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        fixture.server()
                .expect(requestTo(SYSTEM_ONE_URI))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
        var service = fixture.factory().build();
        var request = new PropositionRequest("Evidence", "A proposition");

        assertThat(service.assess(request))
                .isEqualTo(new PropositionResult.Failure(FailureReason.UNAVAILABLE));
        assertThat(service.assess(request))
                .isEqualTo(new PropositionResult.Failure(FailureReason.INVALID_RESPONSE));
        fixture.server().verify();
    }

    private static Fixture fixture() {
        return fixture(TypeSafeModelFactory.DEFAULT_MODEL);
    }

    private static Fixture fixture(String defaultModel) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var factory =
                new TypeSafeModelFactory(
                        TypeSafeClientOptions.defaults(),
                        () -> "test-key",
                        builder,
                        io.micrometer.observation.ObservationRegistry.NOOP,
                        defaultModel);
        return new Fixture(factory, server);
    }

    private record Fixture(TypeSafeModelFactory factory, MockRestServiceServer server) {}
}
