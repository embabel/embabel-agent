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
package com.embabel.agent.jev.api;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.exception.*;
import org.springaicommunity.typesafe.question.*;
import org.springaicommunity.typesafe.response.UnknownAnswer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class JevBoundaryTest {
    static final String GOOD = "{\"answers\":{\"ok\":{\"type\":\"noul\",\"noul\":0.8}}}";
    static void call(TypeSafeClient client) { client.systemOne("PRIVATE_STATE", Map.of("ok", Noul.of("PRIVATE_QUESTION"))); }
    record Fixture(RestClient.Builder builder, MockRestServiceServer server, TypeSafeClient client) { }
    Fixture fixture() {
        var builder = RestClient.builder().messageConverters(c -> c.add(new JacksonJsonHttpMessageConverter()));
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(builder, server, JevClients.create(JevClientOptions.defaults(), () -> "PRIVATE_KEY", builder));
    }
    @ParameterizedTest @ValueSource(strings = {
        "{\"answers\":{\"ok\":{\"type\":\"noul\"}}}",
        "{}", "{\"answers\":{}}", "null", "PRIVATE_BODY",
        "{\"answers\":{\"ok\":{\"type\":\"noul\",\"noul\":0.1,\"noul\":0.9}}}",
        GOOD + " {}",
        "{\"answers\":{\"ok\":{\"type\":\"noul\",\"noul\":\"0.5\"}}}",
        "{\"answers\":{\"ok\":{\"type\":\"noul\",\"noul\":1.1}}}",
        "{\"answers\":{\"ok\":{\"type\":\"noul\",\"noul\":null}}}",
        "{\"answers\":{\"ok\":{\"type\":\"score\",\"score\":1e999}}}",
        "{\"answers\":{\"ok\":{\"type\":\"choice\",\"choice\":\"a\",\"probabilities\":{\"a\":-0.1}}}}"
    })
    void strictPrivateFailures(String body) {
        var f = fixture();
        f.server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON).header("x-typesafe-request-id", "PRIVATE_ID"));
        assertThatThrownBy(() -> call(f.client)).isInstanceOf(TypeSafeException.class)
            .hasMessage("Jev request or response invalid").hasNoCause().satisfies(e -> assertThat(e.getSuppressed()).isEmpty());
        f.server.verify();
    }
    @ParameterizedTest @ValueSource(ints = {301, 400, 401, 429, 503})
    void oneAttemptAndNoErrorBody(int status) {
        var f = fixture();
        f.server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status)).body("PRIVATE_BODY")
            .header("x-typesafe-request-id", "PRIVATE_ID"));
        assertThatThrownBy(() -> call(f.client)).isInstanceOfSatisfying(TypeSafeApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status); assertThat(e.body()).isNull(); assertThat(e.headers().isEmpty()).isTrue();
            assertThat(e.endpoint()).isEmpty(); assertThat(e.getCause()).isNull(); assertThat(e.getMessage()).isEqualTo("Jev HTTP request failed");
        });
        f.server.verify();
    }
    @Test void nativePrimitivesUsageAndForwardCompatibility() {
        var f = fixture();
        f.server.expect(anything()).andRespond(withSuccess("""
            {"model":"jev-latest","usage":{"input_tokens":12,"output_tokens":3},"answers":{
              "n":{"type":"noul","noul":0.8},
              "c":{"type":"choice","choice":"b","probabilities":{"a":0.9,"b":0.1},"confidence":0.4},
              "s":{"type":"score","score":7.3,"probabilities":{"0":0.8,"1":0.1},"confidence":0.4},
              "future":{"type":"future","data":"preserved"}}}
            """, MediaType.APPLICATION_JSON).header("x-typesafe-request-id", "request-123"));
        var result = f.client.systemOne(Map.of("state", "hello"), Map.of("n", Noul.of("n?"),
            "c", Choice.of("c?", "a", "b"), "s", Score.of("s?", "low", "high")));
        assertThat(result.noulValue("n")).isEqualTo(0.8);
        assertThat(result.choiceValue("c")).isEqualTo("b");
        assertThat(result.scoreValue("s")).isEqualTo(7.3);
        assertThat(result.usage().inputTokens()).isEqualTo(12);
        assertThat(result.requestId()).isEqualTo("request-123");
        assertThat(result.answer("future")).isInstanceOf(UnknownAnswer.class);
        f.server.verify();
    }
    @Test void credentialsRotateAndCustomTransportIsPreserved() {
        var builder = RestClient.builder();
        var seen = new AtomicInteger();
        builder.requestInterceptor((request, body, execution) -> { seen.incrementAndGet(); return execution.execute(request, body); });
        var server = MockRestServiceServer.bindTo(builder).build();
        var key = new AtomicReference<>("one");
        var client = JevClients.create(JevClientOptions.defaults(), key::get, builder);
        server.expect(anything()).andExpect(header("Authorization", "Bearer one")).andRespond(withSuccess(GOOD, MediaType.APPLICATION_JSON));
        server.expect(anything()).andExpect(header("Authorization", "Bearer two")).andRespond(withSuccess(GOOD, MediaType.APPLICATION_JSON));
        call(client); key.set("two"); call(client);
        assertThat(seen).hasValue(2); server.verify();
    }
    @Test void interruptionMakesNoRequestAndPreservesFlag() {
        var f = fixture();
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> call(f.client)).isInstanceOf(CancellationException.class); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
        f.server.verify();
    }
    @Test void arbitrarySupplierExceptionsAreNotTrusted() {
        var client = JevClients.create(JevClientOptions.defaults(), () -> { throw new TypeSafeApiException("PRIVATE", 400, "PRIVATE", new HttpHeaders(), "PRIVATE"); });
        assertThatThrownBy(() -> call(client)).hasMessage("Jev request or response invalid").hasNoCause();
    }
    @Test void errorStatusNeverOpensResponseBody() {
        var f = fixture();
        f.server.expect(anything()).andRespond(request -> new org.springframework.mock.http.client.MockClientHttpResponse(new byte[0], HttpStatus.SERVICE_UNAVAILABLE) {
            @Override public java.io.InputStream getBody() { throw new IllegalStateException("PRIVATE_BODY"); }
            @Override public void close() { }
        });
        assertThatThrownBy(() -> call(f.client)).isInstanceOfSatisfying(TypeSafeApiException.class,
                error -> assertThat(error.status()).isEqualTo(503));
    }
    record DatedState(java.time.LocalDate date) { }
    @Test void privateMapperHandlesJavaTimePojoState() {
        var f = fixture();
        f.server.expect(anything()).andExpect(content().string(org.hamcrest.Matchers.containsString("2026-09-24")))
                .andRespond(withSuccess(GOOD, MediaType.APPLICATION_JSON));
        var response = f.client.systemOne(org.springaicommunity.typesafe.JsonContent.of(
                new DatedState(java.time.LocalDate.of(2026, 9, 24))), Map.of("ok", Noul.of("ok?")));
        assertThat(response.noulValue("ok")).isEqualTo(0.8);
        f.server.verify();
    }
    @Test void logsNeverRetainPrivateFailures() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.embabel.agent.jev.internal.GuardedJevApi");
        var oldLevel = logger.getLevel();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender); logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            var f = fixture();
            f.server.expect(anything()).andRespond(withSuccess("PRIVATE_BODY", MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> call(f.client)).isInstanceOf(TypeSafeException.class);
            assertThat(appender.list).hasSize(1).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE", "https", "jev-latest");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally { logger.detachAppender(appender); logger.setLevel(oldLevel); appender.stop(); }
    }
    @Test void modelsUsesSameGuard() {
        var f = fixture();
        f.server.expect(requestTo("https://api.typesafe.ai/v1/models")).andRespond(withSuccess("PRIVATE", MediaType.APPLICATION_JSON));
        assertThatThrownBy(f.client::listModels).hasMessage("Jev request or response invalid").hasNoCause();
    }
    @Test void observationsHaveOnlyFixedValues() {
        var contexts = new ArrayList<Observation.Context>();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context c) { return true; }
            public void onStop(Observation.Context c) { contexts.add(c); }
        });
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var client = JevClients.create(JevClientOptions.defaults(), () -> "PRIVATE", builder, registry);
        server.expect(anything()).andRespond(withSuccess(GOOD, MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("PRIVATE"));
        call(client); assertThatThrownBy(() -> call(client)).isInstanceOf(TypeSafeApiException.class);
        assertThat(contexts).hasSize(2);
        assertThat(contexts.getFirst().getLowCardinalityKeyValues().toString()).contains("success", "2xx", "systemone").doesNotContain("PRIVATE");
        assertThat(contexts.getLast().getLowCardinalityKeyValues().toString()).contains("failure", "4xx");
        assertThat(contexts).allSatisfy(c -> { assertThat(c.getHighCardinalityKeyValues()).isEmpty(); assertThat(c.getError()).isNull(); });
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void realSocketResponseLimits(boolean chunked) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = (GOOD + " ".repeat(2000)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, chunked ? 0 : bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var options = options(server.getAddress().getPort(), Duration.ofSeconds(2), 100);
            assertThatThrownBy(() -> call(JevClients.create(options, () -> "key"))).isInstanceOf(TypeSafeException.class).hasNoCause();
        } finally { server.stop(0); }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void realSocketReadTimeout(boolean bodyStalls) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            if (bodyStalls) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
            }
        });
        server.start();
        try {
            var client = JevClients.create(options(server.getAddress().getPort(), Duration.ofMillis(80), 1024), () -> "key");
            assertThatThrownBy(() -> call(client)).isInstanceOf(TypeSafeApiTimeoutException.class).hasNoCause();
        } finally { server.stop(0); }
    }
    @Test void fallbackDoesNotFollowRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var redirected = new AtomicInteger();
        server.createContext("/v1/models", exchange -> {
            exchange.getResponseHeaders().add("Location", "/unexpected");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/unexpected", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            var client = JevClients.create(options(server.getAddress().getPort(), Duration.ofSeconds(1), 1024), () -> "key");
            assertThatThrownBy(client::listModels).isInstanceOfSatisfying(TypeSafeApiException.class,
                    error -> assertThat(error.status()).isEqualTo(302));
            assertThat(redirected).hasValue(0);
        } finally { server.stop(0); }
    }
    @Test void realRefusedConnection() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        var client = JevClients.create(options(port, Duration.ofSeconds(1), 1024), () -> "key");
        assertThatThrownBy(() -> call(client)).isInstanceOf(TypeSafeApiConnectionException.class).hasNoCause();
    }
    static JevClientOptions options(int port, Duration timeout, int limit) {
        return new JevClientOptions(URI.create("http://127.0.0.1:" + port), "jev-latest", timeout, timeout, limit);
    }
    @ParameterizedTest @ValueSource(strings = {"http://example.com", "https://user:secret@example.com", "https://example.com/path", "https://example.com?key=secret"})
    void invalidOrigins(String origin) {
        assertThatThrownBy(() -> new JevClientOptions(URI.create(origin), "jev-latest", Duration.ofSeconds(1), Duration.ofSeconds(1), 1024)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
    }
    @Test void invalidOptions() {
        assertThatThrownBy(() -> options(80, Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> options(80, Duration.ofSeconds(1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JevClientOptions(URI.create("https://example.com"), " ", Duration.ofSeconds(1), Duration.ofSeconds(1), 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
