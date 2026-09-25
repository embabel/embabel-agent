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
package com.embabel.agent.typesafe.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.exception.TypeSafeApiConnectionException;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import org.springaicommunity.typesafe.exception.TypeSafeApiTimeoutException;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.response.UnknownAnswer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class TypeSafeBoundaryTest {
    static final String GOOD =
            """
            {"answers":{"ok":{"type":"noul","noul":0.8}}}
            """;

    static void call(TypeSafeClient client) {
        client.systemOne("PRIVATE_STATE", Map.of("ok", Noul.of("PRIVATE_QUESTION")));
    }

    record Fixture(
            RestClient.Builder builder, MockRestServiceServer server, TypeSafeClient client) {}

    Fixture fixture() {
        var builder =
                RestClient.builder()
                        .configureMessageConverters(
                                c ->
                                        c.registerDefaults()
                                                .withJsonConverter(
                                                        new JacksonJsonHttpMessageConverter()));
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(
                builder,
                server,
                TypeSafeClients.create(
                        TypeSafeClientOptions.defaults(), () -> "PRIVATE_KEY", builder));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                """
                {"answers":{"ok":{"type":"noul"}}}
                """,
                "{}",
                """
                {"answers":{}}
                """,
                "null",
                "PRIVATE_BODY",
                """
                {"answers":{"ok":{"type":"noul","noul":0.1,"noul":0.9}}}
                """,
                GOOD + " {}",
                """
                {"answers":{"ok":{"type":"noul","noul":"0.5"}}}
                """,
                """
                {"answers":{"ok":{"type":"noul","noul":1.1}}}
                """,
                """
                {"answers":{"ok":{"type":"noul","noul":null}}}
                """,
                """
                {"answers":{"ok":{"type":"score","score":1e999}}}
                """,
                """
                {"answers":{"ok":{"type":"choice","choice":"a","probabilities":{"a":-0.1}}}}
                """
            })
    void strictPrivateFailures(String body) {
        var f = fixture();
        f.server
                .expect(anything())
                .andRespond(
                        withSuccess(body, MediaType.APPLICATION_JSON)
                                .header("x-typesafe-request-id", "PRIVATE_ID"));
        assertThatThrownBy(() -> call(f.client))
                .isInstanceOf(TypeSafeException.class)
                .hasMessage("TypeSafe request or response invalid")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
        f.server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 400, 401, 429, 503})
    void oneAttemptAndNoErrorBody(int status) {
        var f = fixture();
        f.server
                .expect(anything())
                .andRespond(
                        withStatus(HttpStatusCode.valueOf(status))
                                .body("PRIVATE_BODY")
                                .header("x-typesafe-request-id", "PRIVATE_ID"));
        assertThatThrownBy(() -> call(f.client))
                .isInstanceOfSatisfying(
                        TypeSafeApiException.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(status);
                            assertThat(e.body()).isNull();
                            assertThat(e.headers().isEmpty()).isTrue();
                            assertThat(e.endpoint()).isEmpty();
                            assertThat(e.getCause()).isNull();
                            assertThat(e.getMessage()).isEqualTo("TypeSafe HTTP request failed");
                        });
        f.server.verify();
    }

    @Test
    void nativePrimitivesUsageAndForwardCompatibility() {
        var f = fixture();
        f.server
                .expect(anything())
                .andRespond(
                        withSuccess(
                                        """
                                        {"model":"jev-latest","usage":{"input_tokens":12,"output_tokens":3},"answers":{
                                          "n":{"type":"noul","noul":0.8},
                                          "c":{"type":"choice","choice":"b","probabilities":{"a":0.9,"b":0.1},"confidence":0.4},
                                          "s":{"type":"score","score":7.3,"probabilities":{"0":0.8,"1":0.1},"confidence":0.4},
                                          "future":{"type":"future","data":"preserved"}}}
                                        """,
                                        MediaType.APPLICATION_JSON)
                                .header("x-typesafe-request-id", "request-123"));
        var result =
                f.client.systemOne(
                        Map.of("state", "hello"),
                        Map.of(
                                "n",
                                Noul.of("n?"),
                                "c",
                                Choice.of("c?", "a", "b"),
                                "s",
                                Score.of("s?", "low", "high")));
        assertThat(result.noulValue("n")).isEqualTo(0.8);
        assertThat(result.choiceValue("c")).isEqualTo("b");
        assertThat(result.scoreValue("s")).isEqualTo(7.3);
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.requestId()).isEqualTo("request-123");
        assertThat(result.answer("future")).isInstanceOf(UnknownAnswer.class);
        f.server.verify();
    }

    @Test
    void credentialsRotateAndCustomTransportIsPreserved() {
        var builder = RestClient.builder();
        var seen = new AtomicInteger();
        builder.requestInterceptor(
                (request, body, execution) -> {
                    seen.incrementAndGet();
                    return execution.execute(request, body);
                });
        var server = MockRestServiceServer.bindTo(builder).build();
        var key = new AtomicReference<>("one");
        var client = TypeSafeClients.create(TypeSafeClientOptions.defaults(), key::get, builder);
        server.expect(anything())
                .andExpect(header("Authorization", "Bearer one"))
                .andRespond(withSuccess(GOOD, MediaType.APPLICATION_JSON));
        server.expect(anything())
                .andExpect(header("Authorization", "Bearer two"))
                .andRespond(withSuccess(GOOD, MediaType.APPLICATION_JSON));
        call(client);
        key.set("two");
        call(client);
        assertThat(seen).hasValue(2);
        server.verify();
    }

    @Test
    void interruptionMakesNoRequestAndPreservesFlag() {
        var f = fixture();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> call(f.client)).isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        f.server.verify();
    }

    @Test
    void arbitrarySupplierExceptionsAreNotTrusted() {
        var client =
                TypeSafeClients.create(
                        TypeSafeClientOptions.defaults(),
                        () -> {
                            throw new TypeSafeApiException(
                                    "PRIVATE", 400, "PRIVATE", new HttpHeaders(), "PRIVATE");
                        });
        assertThatThrownBy(() -> call(client))
                .hasMessage("TypeSafe request or response invalid")
                .hasNoCause();
    }

    @Test
    void errorStatusNeverReadsResponseBody() {
        var f = fixture();
        var closed = new AtomicBoolean();
        f.server
                .expect(anything())
                .andRespond(
                        request ->
                                new MockClientHttpResponse(
                                        new byte[0], HttpStatus.SERVICE_UNAVAILABLE) {
                                    @Override
                                    public InputStream getBody() {
                                        return new InputStream() {
                                            @Override
                                            public int read() {
                                                throw new AssertionError(
                                                        "Error body must not be read");
                                            }
                                        };
                                    }

                                    @Override
                                    public void close() {
                                        closed.set(true);
                                    }
                                });
        assertThatThrownBy(() -> call(f.client))
                .isInstanceOfSatisfying(
                        TypeSafeApiException.class,
                        error -> assertThat(error.status()).isEqualTo(503));
        assertThat(closed).isTrue();
    }

    record DatedState(LocalDate date) {}

    @Test
    void privateMapperHandlesJavaTimePojoState() {
        var f = fixture();
        f.server
                .expect(anything())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("2026-09-24")))
                .andRespond(withSuccess(GOOD, MediaType.APPLICATION_JSON));
        var response =
                f.client.systemOne(
                        JsonContent.of(new DatedState(LocalDate.of(2026, 9, 24))),
                        Map.of("ok", Noul.of("ok?")));
        assertThat(response.noulValue("ok")).isEqualTo(0.8);
        f.server.verify();
    }

    @Test
    void logsNeverRetainPrivateFailures() {
        var logger =
                (Logger)
                        LoggerFactory.getLogger(
                                "com.embabel.agent.typesafe.internal.GuardedTypeSafeApi");
        var oldLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            var f = fixture();
            f.server
                    .expect(anything())
                    .andRespond(withSuccess("PRIVATE_BODY", MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> call(f.client)).isInstanceOf(TypeSafeException.class);
            assertThat(appender.list)
                    .hasSize(1)
                    .allSatisfy(
                            event -> {
                                assertThat(event.getFormattedMessage())
                                        .doesNotContain("PRIVATE", "https", "jev-latest");
                                assertThat(event.getThrowableProxy()).isNull();
                            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(oldLevel);
            appender.stop();
        }
    }

    @Test
    void modelsUsesSameGuard() {
        var f = fixture();
        f.server
                .expect(requestTo("https://api.typesafe.ai/v1/models"))
                .andRespond(withSuccess("PRIVATE", MediaType.APPLICATION_JSON));
        assertThatThrownBy(f.client::listModels)
                .hasMessage("TypeSafe request or response invalid")
                .hasNoCause();
    }

    @Test
    void modelsSuccessUsesNativeMetadata() {
        var f = fixture();
        f.server
                .expect(requestTo("https://api.typesafe.ai/v1/models"))
                .andRespond(
                        withSuccess(
                                """
                                {"models":[{"name":"jev-latest","description":"Default model","release_date":"2026-09-24"}]}
                                """,
                                MediaType.APPLICATION_JSON));
        var models = f.client.listModels();
        assertThat(models)
                .singleElement()
                .satisfies(
                        model -> {
                            assertThat(model.name()).isEqualTo("jev-latest");
                            assertThat(model.description()).isEqualTo("Default model");
                            assertThat(model.releaseDate()).isEqualTo("2026-09-24");
                        });
        f.server.verify();
    }

    @Test
    void observationsHaveOnlyFixedValues() {
        var contexts = new ArrayList<Observation.Context>();
        var registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(
                        new ObservationHandler<Observation.Context>() {
                            @Override
                            public boolean supportsContext(Observation.Context c) {
                                return true;
                            }

                            @Override
                            public void onStop(Observation.Context c) {
                                contexts.add(c);
                            }
                        });
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client =
                TypeSafeClients.create(
                        TypeSafeClientOptions.defaults(), () -> "PRIVATE", builder, registry);
        server.expect(anything()).andRespond(withSuccess(GOOD, MediaType.APPLICATION_JSON));
        server.expect(anything())
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("PRIVATE"));
        call(client);
        assertThatThrownBy(() -> call(client)).isInstanceOf(TypeSafeApiException.class);
        assertThat(contexts).hasSize(2);
        assertThat(contexts.getFirst().getLowCardinalityKeyValues().toString())
                .contains("success", "2xx", "systemone")
                .doesNotContain("PRIVATE");
        assertThat(contexts.getLast().getLowCardinalityKeyValues().toString())
                .contains("failure", "4xx");
        assertThat(contexts)
                .allSatisfy(
                        c -> {
                            assertThat(c.getHighCardinalityKeyValues()).isEmpty();
                            assertThat(c.getError()).isNull();
                        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void suppliedBuilderRetainsHttpObservationsWhenTypeSafeUsesNoopRegistry(boolean success) {
        var meters = new SimpleMeterRegistry();
        try {
            var httpRegistry = ObservationRegistry.create();
            httpRegistry
                    .observationConfig()
                    .observationHandler(new DefaultMeterObservationHandler(meters));
            var builder = RestClient.builder().observationRegistry(httpRegistry);
            var server = MockRestServiceServer.bindTo(builder).build();
            // This overload uses NOOP for TypeSafe while retaining the builder's HTTP registry.
            var client =
                    TypeSafeClients.create(
                            TypeSafeClientOptions.defaults(), () -> "PRIVATE_KEY", builder);
            var expectation = server.expect(anything());
            if (success) {
                expectation.andRespond(withSuccess(GOOD, MediaType.APPLICATION_JSON));
                call(client);
            } else {
                expectation.andRespond(
                        withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("PRIVATE_BODY"));
                assertThatThrownBy(() -> call(client)).isInstanceOf(TypeSafeApiException.class);
            }
            var httpTimer = meters.get("http.client.requests").timer();
            assertThat(httpTimer.count()).isEqualTo(1);
            assertThat(httpTimer.getId().getTag("status")).isEqualTo(success ? "200" : "503");
            assertThat(meters.find("embabel.typesafe.request").meters()).isEmpty();
            assertThat(httpRegistry.getCurrentObservation()).isNull();
            server.verify();
        } finally {
            meters.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void httpObservationIsChildOfTypeSafeAndCallerScopeIsRestored(boolean success) {
        var contexts = new ArrayList<Observation.Context>();
        var registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(
                        new ObservationHandler<Observation.Context>() {
                            @Override
                            public boolean supportsContext(Observation.Context context) {
                                return true;
                            }

                            @Override
                            public void onStop(Observation.Context context) {
                                contexts.add(context);
                            }
                        });
        var builder = RestClient.builder().observationRegistry(registry);
        var server = MockRestServiceServer.bindTo(builder).build();
        var client =
                TypeSafeClients.create(
                        TypeSafeClientOptions.defaults(), () -> "PRIVATE_KEY", builder, registry);
        var expectation = server.expect(anything());
        if (success) {
            expectation.andRespond(withSuccess(GOOD, MediaType.APPLICATION_JSON));
        } else {
            expectation.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("PRIVATE_BODY"));
        }
        var caller = Observation.start("caller.workflow", registry);
        try (var scope = caller.openScope()) {
            if (success) {
                call(client);
            } else {
                assertThatThrownBy(() -> call(client)).isInstanceOf(TypeSafeApiException.class);
            }
            assertThat(registry.getCurrentObservation()).isSameAs(caller);
        } finally {
            caller.stop();
        }
        assertThat(registry.getCurrentObservation()).isNull();
        var http =
                contexts.stream()
                        .filter(context -> context.getName().equals("http.client.requests"))
                        .findFirst()
                        .orElseThrow();
        assertThat(http.getParentObservation()).isNotNull();
        assertThat(http.getParentObservation().getContextView().getName())
                .isEqualTo("embabel.typesafe.request");
        var providerObservation =
                contexts.stream()
                        .filter(context -> context.getName().equals("embabel.typesafe.request"))
                        .findFirst()
                        .orElseThrow();
        assertThat(providerObservation.getParentObservation()).isSameAs(caller);
        server.verify();
    }

    @Test
    void fallbackEmitsHttpObservationUnderTypeSafeObservation() throws Exception {
        var contexts = new ArrayList<Observation.Context>();
        var registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(
                        new ObservationHandler<Observation.Context>() {
                            @Override
                            public boolean supportsContext(Observation.Context context) {
                                return true;
                            }

                            @Override
                            public void onStop(Observation.Context context) {
                                contexts.add(context);
                            }
                        });
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/systemone",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    byte[] body = GOOD.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();
        try {
            var client =
                    TypeSafeClients.create(
                            options(server.getAddress().getPort(), Duration.ofSeconds(1), 1024),
                            () -> "PRIVATE_KEY",
                            null,
                            registry);
            call(client);
            assertThat(contexts)
                    .extracting(Observation.Context::getName)
                    .containsExactlyInAnyOrder("http.client.requests", "embabel.typesafe.request");
            var http =
                    contexts.stream()
                            .filter(context -> context.getName().equals("http.client.requests"))
                            .findFirst()
                            .orElseThrow();
            assertThat(http.getParentObservation()).isNotNull();
            assertThat(http.getParentObservation().getContextView().getName())
                    .isEqualTo("embabel.typesafe.request");
            assertThat(registry.getCurrentObservation()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void callerCanUseVirtualThreads() throws Exception {
        var fixture = fixture();
        fixture.server.expect(anything()).andRespond(withSuccess(GOOD, MediaType.APPLICATION_JSON));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var completed =
                    executor.submit(
                            () -> {
                                call(fixture.client);
                                return Thread.currentThread().isVirtual();
                            });
            assertThat(completed.get(2, TimeUnit.SECONDS)).isTrue();
        }
        fixture.server.verify();
    }

    @Test
    void realMeterHandlerRecordsOneSafeTimerPerInvocation() {
        var meters = new SimpleMeterRegistry();
        try {
            var registry = ObservationRegistry.create();
            registry.observationConfig()
                    .observationHandler(
                            new io.micrometer.core.instrument.observation
                                    .DefaultMeterObservationHandler(meters));
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var options =
                    new TypeSafeClientOptions(
                            URI.create("https://private.example.invalid"),
                            "PRIVATE_MODEL",
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1),
                            1024);
            var client = TypeSafeClients.create(options, () -> "PRIVATE_KEY", builder, registry);
            for (String operation : List.of("systemone", "models")) {
                for (boolean success : List.of(true, false)) {
                    var expectation =
                            server.expect(
                                    requestTo("https://private.example.invalid/v1/" + operation));
                    if (success) {
                        expectation.andRespond(
                                withSuccess(
                                                operation.equals("systemone")
                                                        ? GOOD
                                                        : """
                                                        {"models":[{"name":"PRIVATE_MODEL"}]}
                                                        """,
                                                MediaType.APPLICATION_JSON)
                                        .header("x-typesafe-request-id", "PRIVATE_REQUEST_ID"));
                    } else {
                        expectation.andRespond(
                                withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("PRIVATE_BODY"));
                    }
                }
            }
            int invocations = 0;
            for (String operation : List.of("systemone", "models")) {
                for (boolean success : List.of(true, false)) {
                    Runnable invoke =
                            operation.equals("systemone") ? () -> call(client) : client::listModels;
                    if (success) {
                        invoke.run();
                    } else {
                        assertThatThrownBy(invoke::run).isInstanceOf(TypeSafeApiException.class);
                    }
                    invocations++;
                    var timer =
                            meters.get("embabel.typesafe.request")
                                    .tags(
                                            "operation",
                                            operation,
                                            "outcome",
                                            success ? "success" : "failure",
                                            "status.family",
                                            success ? "2xx" : "5xx")
                                    .timer();
                    assertThat(timer.count()).isEqualTo(1);
                    assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isPositive();
                    assertThat(timer.getId().getTags())
                            .containsExactlyInAnyOrder(
                                    Tag.of("operation", operation),
                                    Tag.of("outcome", success ? "success" : "failure"),
                                    Tag.of("status.family", success ? "2xx" : "5xx"),
                                    Tag.of("error", "none"));
                    assertThat(
                                    meters.find("embabel.typesafe.request").timers().stream()
                                            .mapToLong(Timer::count)
                                            .sum())
                            .isEqualTo(invocations);
                }
            }
            assertThat(meters.getMeters())
                    .isNotEmpty()
                    .allSatisfy(
                            meter ->
                                    assertThat(meter.getId().getTags())
                                            .isNotEmpty()
                                            .allSatisfy(
                                                    tag -> {
                                                        assertThat(tag.getKey())
                                                                .isIn(
                                                                        "operation",
                                                                        "outcome",
                                                                        "status.family",
                                                                        "error");
                                                        assertThat(tag.getValue())
                                                                .isIn(
                                                                        "systemone",
                                                                        "models",
                                                                        "success",
                                                                        "failure",
                                                                        "2xx",
                                                                        "5xx",
                                                                        "none");
                                                    }));
            server.verify();
        } finally {
            meters.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realSocketResponseLimits(boolean chunked) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/systemone",
                exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    byte[] bytes = (GOOD + " ".repeat(2000)).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, chunked ? 0 : bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        try {
            var options = options(server.getAddress().getPort(), Duration.ofSeconds(2), 100);
            var client = TypeSafeClients.create(options, () -> "key");
            assertThatThrownBy(() -> call(client))
                    .isInstanceOf(TypeSafeException.class)
                    .hasNoCause();
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realSocketReadTimeout(boolean bodyStalls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/systemone",
                exchange -> {
                    if (bodyStalls) {
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('{');
                        exchange.getResponseBody().flush();
                    }
                });
        server.start();
        try {
            var client =
                    TypeSafeClients.create(
                            options(server.getAddress().getPort(), Duration.ofMillis(80), 1024),
                            () -> "key");
            assertThatThrownBy(() -> call(client))
                    .isInstanceOf(TypeSafeApiTimeoutException.class)
                    .hasNoCause();
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 503})
    void closesRejectedBodyBeforeResponse(int status) {
        var builder =
                RestClient.builder()
                        .configureMessageConverters(
                                converters ->
                                        converters
                                                .registerDefaults()
                                                .withJsonConverter(
                                                        new JacksonJsonHttpMessageConverter()));
        var server = MockRestServiceServer.bindTo(builder).build();
        var bodyClosed = new AtomicBoolean();
        var responseClosed = new AtomicBoolean();
        byte[] responseBody = (GOOD + " ".repeat(200)).getBytes(StandardCharsets.UTF_8);
        server.expect(anything())
                .andRespond(
                        request ->
                                new MockClientHttpResponse(
                                        responseBody, HttpStatusCode.valueOf(status)) {
                                    private final InputStream body =
                                            new ByteArrayInputStream(responseBody) {
                                                @Override
                                                public void close() throws IOException {
                                                    bodyClosed.set(true);
                                                    super.close();
                                                }
                                            };

                                    @Override
                                    public InputStream getBody() {
                                        return body;
                                    }

                                    @Override
                                    public void close() {
                                        assertThat(bodyClosed).isTrue();
                                        responseClosed.set(true);
                                    }
                                });
        var timeout = Duration.ofSeconds(1);
        var options =
                new TypeSafeClientOptions(
                        URI.create("https://api.typesafe.ai"), "jev-latest", timeout, timeout, 100);
        var client = TypeSafeClients.create(options, () -> "key", builder);

        assertThatThrownBy(() -> call(client))
                .isInstanceOf(TypeSafeException.class)
                .hasNoCause()
                .satisfies(
                        error -> {
                            if (status == 503) {
                                assertThat(((TypeSafeApiException) error).status()).isEqualTo(503);
                            }
                            assertThat(error.getSuppressed()).isEmpty();
                        });
        assertThat(bodyClosed).isTrue();
        assertThat(responseClosed).isTrue();
        server.verify();
    }

    @Test
    void fallbackDoesNotFollowRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var redirected = new AtomicInteger();
        server.createContext(
                "/v1/models",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", "/unexpected");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/unexpected",
                exchange -> {
                    redirected.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();
        try {
            var client =
                    TypeSafeClients.create(
                            options(server.getAddress().getPort(), Duration.ofSeconds(1), 1024),
                            () -> "key");
            assertThatThrownBy(client::listModels)
                    .isInstanceOfSatisfying(
                            TypeSafeApiException.class,
                            error -> assertThat(error.status()).isEqualTo(302));
            assertThat(redirected).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void realRefusedConnection() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var client =
                TypeSafeClients.create(options(port, Duration.ofSeconds(1), 1024), () -> "key");
        assertThatThrownBy(() -> call(client))
                .isInstanceOf(TypeSafeApiConnectionException.class)
                .hasNoCause();
    }

    static TypeSafeClientOptions options(int port, Duration timeout, int limit) {
        return new TypeSafeClientOptions(
                URI.create("http://127.0.0.1:" + port), "jev-latest", timeout, timeout, limit);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/relative",
                "file:///tmp/typesafe",
                "https:/missing-host",
                "https://user:secret@example.com",
                "https://example.com?key=secret",
                "https://example.com#secret"
            })
    void invalidBaseUris(String origin) {
        var uri = URI.create(origin);
        var timeout = Duration.ofSeconds(1);
        assertThatThrownBy(
                        () -> new TypeSafeClientOptions(uri, "jev-latest", timeout, timeout, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    void invalidOptions() {
        var timeout = Duration.ofSeconds(1);
        assertThatThrownBy(() -> options(80, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> options(80, timeout, 0))
                .isInstanceOf(IllegalArgumentException.class);
        var uri = URI.create("https://example.com");
        assertThatThrownBy(() -> new TypeSafeClientOptions(uri, " ", timeout, timeout, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://provider.example",
                "https://provider.example/proxy",
                "HTTPS://provider.example"
            })
    void configuredProviderEndpoints(String baseUrl) {
        var timeout = Duration.ofSeconds(1);
        var options =
                new TypeSafeClientOptions(
                        URI.create(baseUrl), "jev-latest", timeout, timeout, 1024);
        assertThat(options.baseUri()).hasToString(baseUrl);
    }

    @Test
    void timeoutBoundsMatchFallbackTransport() {
        assertThat(options(80, Duration.ofMillis(1), 1).readTimeout())
                .isEqualTo(Duration.ofMillis(1));
        assertThat(options(80, Duration.ofMillis(Integer.MAX_VALUE), 1).readTimeout())
                .isEqualTo(Duration.ofMillis(Integer.MAX_VALUE));
        for (Duration timeout :
                List.of(
                        Duration.ofNanos(1),
                        Duration.ofMillis(-1),
                        Duration.ofMillis(Integer.MAX_VALUE).plusNanos(1),
                        Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> options(80, timeout, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> options(80, null, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
