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
package com.embabel.agent.jev.internal;

import com.embabel.agent.jev.api.JevClientOptions;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.HttpURLConnection;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.*;
import org.springaicommunity.typesafe.api.TypeSafeApi;
import org.springaicommunity.typesafe.exception.*;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Internal boundary: unsafe transport failures never reach SDK retry diagnostics. */
public final class GuardedJevApi extends TypeSafeApi {
    private final ObservationRegistry registry;

    public GuardedJevApi(JevClientOptions options, Supplier<String> keys, RestClient.Builder builder,
                         ObservationRegistry registry) {
        super(options.baseUri().toString(), () -> {
            String key = keys.get();
            if (key == null || key.isBlank() || key.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Jev credential unavailable");
            return key;
        }, new HttpHeaders(), "/v1/systemone", "/v1/models", configure(options, builder), new SafeErrors());
        this.registry = registry;
    }

    private static RestClient.Builder configure(JevClientOptions options, RestClient.Builder supplied) {
        RestClient.Builder builder;
        if (supplied == null) {
            var factory = new SimpleClientHttpRequestFactory() {
                @Override
                protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                    super.prepareConnection(connection, method);
                    connection.setInstanceFollowRedirects(false);
                }
            };
            factory.setConnectTimeout(options.connectTimeout());
            factory.setReadTimeout(options.readTimeout());
            builder = RestClient.builder().requestFactory(factory);
        } else builder = supplied.clone();
        var mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
        builder.messageConverters(converters -> converters.add(0, new JacksonJsonHttpMessageConverter(mapper)));
        builder.requestInterceptors(interceptors -> interceptors.add(0, BoundedResponse.interceptor(options.maxResponseBytes())));
        return builder;
    }

    @Override public ResponseEntity<SystemOneResponse> systemOne(SystemOneRequest request) {
        return guarded("systemone", () -> {
            var response = super.systemOne(request);
            if (response.getBody() == null || response.getBody().answers() == null || response.getBody().answers().isEmpty())
                throw new IllegalArgumentException();
            for (Answer answer : response.getBody().answers().values()) {
                switch (answer) {
                    case NoulAnswer n -> probability(n.value());
                    case ChoiceAnswer c -> { probability(c.confidence()); c.probabilities().values().forEach(GuardedJevApi::probability); }
                    case ScoreAnswer s -> {
                        if (!Double.isFinite(s.value())) throw new IllegalArgumentException();
                        probability(s.confidence()); s.probabilities().values().forEach(GuardedJevApi::probability);
                    }
                    case UnknownAnswer ignored -> { }
                    case null -> throw new IllegalArgumentException();
                }
            }
            return response;
        });
    }

    private static void probability(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException();
    }

    @Override public ResponseEntity<ListModelsResponse> listModels() {
        return guarded("models", () -> {
            var response = super.listModels();
            if (response.getBody() == null) throw new IllegalArgumentException();
            return response;
        });
    }

    private <T extends ResponseEntity<?>> T guarded(String operation, Supplier<T> call) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Jev request cancelled");
        var observation = Observation.createNotStarted("embabel.jev.request", registry)
                .lowCardinalityKeyValue("operation", operation).start();
        String outcome = "failure";
        String family = "none";
        try {
            T response = call.get();
            family = response.getStatusCode().value() / 100 + "xx";
            outcome = "success";
            return response;
        } catch (SafeHttpFailure failure) {
            family = failure.status() / 100 + "xx";
            throw failure;
        } catch (RuntimeException failure) {
            if (failure instanceof RestClientException) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException)
                        throw new TypeSafeApiTimeoutException("Jev transport timed out", null, null);
                }
                if (failure instanceof ResourceAccessException)
                    throw new TypeSafeApiConnectionException("Jev connection failed", null);
            }
            throw new TypeSafeException("Jev request or response invalid");
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome).lowCardinalityKeyValue("status.family", family).stop();
            LoggerFactory.getLogger(GuardedJevApi.class).debug("Jev operation={} outcome={} status.family={}", operation, outcome, family);
        }
    }

    private static final class SafeHttpFailure extends TypeSafeApiException {
        SafeHttpFailure(int status) { super("Jev HTTP request failed", status, null, HttpHeaders.EMPTY, ""); }
    }

    private static final class SafeErrors implements ResponseErrorHandler {
        @Override public boolean hasError(ClientHttpResponse response) throws IOException {
            return !response.getStatusCode().is2xxSuccessful();
        }
        @Override public void handleError(URI uri, HttpMethod method, ClientHttpResponse response) throws IOException {
            throw new SafeHttpFailure(response.getStatusCode().value());
        }
    }
}
