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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.PropositionRequest;
import com.embabel.common.ai.decision.PropositionResult;
import com.embabel.common.byok.InvalidApiKeyException;

import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

class TypeSafeModelFactoryTest {

    private static final String NOUL =
            "{\"model\":\"jev-2\",\"answers\":{\"q1\":{\"type\":\"noul\",\"noul\":0.8}}}";

    private record Mocked(TypeSafeModelFactory factory, MockRestServiceServer server) {}

    private static Mocked mocked(TypeSafeClientOptions options, Supplier<String> key) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Mocked(
                new TypeSafeModelFactory(options, key, builder, ObservationRegistry.NOOP), server);
    }

    private static PropositionResult assess(TypeSafeModelFactory factory) {
        return factory.build().assess(new PropositionRequest("charged twice", "Is this urgent?"));
    }

    @Nested
    class Preset {

        @Test
        void asksJevLatestAtTypeSafeWithTheKey() {
            var mocked = mocked(TypeSafeClientOptions.defaults(), () -> "test-key");
            var body = new StringBuilder();
            mocked.server()
                    .expect(requestTo("https://api.typesafe.ai/v1/systemone"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("Authorization", "Bearer test-key"))
                    .andExpect(request -> body.append(((MockClientHttpRequest) request).getBodyAsString()))
                    .andRespond(
                            withSuccess(NOUL, MediaType.APPLICATION_JSON)
                                    .header(TypeSafeModelFactory.REQUEST_ID_HEADER, "req-7"));

            var result = assess(mocked.factory());

            mocked.server().verify();
            assertThat(body.toString()).contains("\"model\":\"jev-latest\"");
            assertThat(result)
                    .isEqualTo(
                            new PropositionResult.Answered(
                                    true, new ModelProvenance("jev-2", "TypeSafe", null, "req-7"), 0.8));
        }

        @Test
        void keepsAConfiguredBasePath() {
            var options =
                    new TypeSafeClientOptions(
                            URI.create("http://proxy.example/typesafe"),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1),
                            4096);
            var mocked = mocked(options, () -> "test-key");
            mocked.server()
                    .expect(requestTo("http://proxy.example/typesafe/v1/systemone"))
                    .andRespond(withSuccess(NOUL, MediaType.APPLICATION_JSON));

            assess(mocked.factory());

            mocked.server().verify();
        }

        @Test
        void aMissingKeyFailsTheCallWithoutSendingIt() {
            var mocked = mocked(TypeSafeClientOptions.defaults(), () -> " ");

            assertThat(assess(mocked.factory()))
                    .isEqualTo(new PropositionResult.Failure(FailureReason.UNAVAILABLE));
            mocked.server().verify();
        }

        @Test
        void buildingNeverReadsTheKey() {
            var calls = new AtomicInteger();
            var factory = new TypeSafeModelFactory(() -> "k" + calls.incrementAndGet());

            var service = factory.build("jev-2");

            assertThat(calls).hasValue(0);
            assertThat(service.getName()).isEqualTo("jev-2");
            assertThat(service.getProvider()).isEqualTo(TypeSafeModelFactory.PROVIDER);
            assertThat(factory.build().getName()).isEqualTo(TypeSafeModelFactory.DEFAULT_MODEL);
        }
    }

    @Nested
    class Byok {

        @Test
        void aBlankKeyIsInvalidWithoutCallingTypeSafe() {
            var mocked = mocked(TypeSafeClientOptions.defaults(), () -> "");
            var factory = mocked.factory();

            assertThatThrownBy(factory::buildValidated)
                    .isInstanceOf(InvalidApiKeyException.class)
                    .hasMessage("TypeSafe credential is missing");
            mocked.server().verify();
        }

        @Test
        void aRejectedKeyIsInvalid() {
            var mocked = mocked(TypeSafeClientOptions.defaults(), () -> "SECRET");
            mocked.server()
                    .expect(requestTo("https://api.typesafe.ai/v1/models"))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
            var factory = mocked.factory();

            assertThatThrownBy(factory::buildValidated)
                    .isInstanceOf(InvalidApiKeyException.class)
                    .hasMessageNotContaining("SECRET");
        }

        @Test
        void anAcceptedKeyReturnsTheDefaultModel() {
            var mocked = mocked(TypeSafeClientOptions.defaults(), () -> "test-key");
            mocked.server()
                    .expect(requestTo("https://api.typesafe.ai/v1/models"))
                    .andRespond(
                            withSuccess(
                                    "{\"models\":[{\"name\":\"jev-latest\",\"description\":\"Jev\",\"release_date\":\"2026-09-01\"}]}",
                                    MediaType.APPLICATION_JSON));

            assertThat(mocked.factory().buildValidated().getName())
                    .isEqualTo(TypeSafeModelFactory.DEFAULT_MODEL);
        }
    }

    @Nested
    class Options {

        @Test
        void rejectsAddressesThatCouldCarryCredentials() {
            for (var uri :
                    new String[] {
                        "https://user:secret@api.typesafe.ai",
                        "https://api.typesafe.ai?key=secret",
                        "https://api.typesafe.ai#secret"
                    }) {
                var address = URI.create(uri);
                var timeout = Duration.ofSeconds(1);
                assertThatThrownBy(() -> new TypeSafeClientOptions(address, timeout, timeout, 1024))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageNotContaining("secret");
            }
        }

        @Test
        void rejectsUnusableTimeoutsAndLimits() {
            var uri = URI.create("https://api.typesafe.ai");
            var second = Duration.ofSeconds(1);
            assertThatThrownBy(() -> new TypeSafeClientOptions(uri, Duration.ZERO, second, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TypeSafeClientOptions(uri, second, second, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsMissingSettingsAsIllegalArguments() {
            var uri = URI.create("https://api.typesafe.ai");
            var second = Duration.ofSeconds(1);
            assertThatThrownBy(() -> new TypeSafeClientOptions(null, second, second, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TypeSafeClientOptions(uri, null, second, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TypeSafeClientOptions(uri, second, null, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void toStringLeavesTheAddressOut() {
            var options =
                    new TypeSafeClientOptions(
                            URI.create("https://private-gateway.example"),
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1),
                            1024);

            assertThat(options.toString()).doesNotContain("private-gateway");
        }
    }
}
