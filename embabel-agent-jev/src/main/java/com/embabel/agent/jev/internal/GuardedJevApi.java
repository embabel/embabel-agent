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

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.api.TypeSafeApi;
import org.springaicommunity.typesafe.exception.TypeSafeApiConnectionException;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import org.springaicommunity.typesafe.exception.TypeSafeApiTimeoutException;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.ListModelsResponse;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springaicommunity.typesafe.response.UnknownAnswer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Serial;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Internal boundary: unsafe transport failures never reach SDK retry diagnostics. */
public final class GuardedJevApi extends TypeSafeApi {
    private static final Logger logger = LoggerFactory.getLogger(GuardedJevApi.class);
    private static final String OBSERVATION_NAME = "embabel.jev.request";
    private static final String TAG_OPERATION = "operation";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_STATUS_FAMILY = "status.family";
    private static final String SYSTEM_ONE_OPERATION = "systemone";
    private static final String MODELS_OPERATION = "models";
    private static final String SYSTEM_ONE_PATH = "/v1/systemone";
    private static final String MODELS_PATH = "/v1/models";
    private static final String SUCCESS = "success";
    private static final String FAILURE = "failure";
    private static final String NO_STATUS = "none";

    private final ObservationRegistry registry;

    public GuardedJevApi(
            JevClientOptions options,
            Supplier<String> keys,
            RestClient.@Nullable Builder builder,
            ObservationRegistry registry) {
        super(
                options.baseUri().toString(),
                () -> {
                    String key = keys.get();
                    if (key == null
                            || key.isBlank()
                            || key.chars().anyMatch(Character::isISOControl)) {
                        throw new IllegalArgumentException("Jev credential unavailable");
                    }
                    return key;
                },
                new HttpHeaders(),
                SYSTEM_ONE_PATH,
                MODELS_PATH,
                configure(options, builder, registry),
                new SafeErrors());
        this.registry = registry;
    }

    private static RestClient.Builder configure(
            JevClientOptions options,
            RestClient.@Nullable Builder supplied,
            ObservationRegistry registry) {
        RestClient.Builder builder;
        if (supplied == null) {
            var factory =
                    new SimpleClientHttpRequestFactory() {
                        @Override
                        protected void prepareConnection(
                                HttpURLConnection connection, String method) throws IOException {
                            super.prepareConnection(connection, method);
                            connection.setInstanceFollowRedirects(false);
                        }
                    };
            factory.setConnectTimeout(options.connectTimeout());
            factory.setReadTimeout(options.readTimeout());
            builder = RestClient.builder().requestFactory(factory).observationRegistry(registry);
        } else {
            builder = supplied.clone();
        }
        var mapper =
                JsonMapper.builder()
                        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                        .build();
        builder.configureMessageConverters(
                converters ->
                        converters
                                .registerDefaults()
                                .configureMessageConvertersList(
                                        list ->
                                                list.add(
                                                        0,
                                                        new JacksonJsonHttpMessageConverter(
                                                                mapper))));
        builder.requestInterceptors(
                interceptors ->
                        interceptors.add(
                                0, BoundedResponse.interceptor(options.maxResponseBytes())));
        return builder;
    }

    @Override
    public ResponseEntity<SystemOneResponse> systemOne(SystemOneRequest request) {
        return guarded(
                SYSTEM_ONE_OPERATION,
                () -> {
                    var response = super.systemOne(request);
                    if (response.getBody() == null
                            || response.getBody().answers() == null
                            || response.getBody().answers().isEmpty()) {
                        throw new IllegalArgumentException("Jev response must contain answers");
                    }
                    for (Answer answer : response.getBody().answers().values()) {
                        switch (answer) {
                            case NoulAnswer(double value) -> probability(value);
                            case ChoiceAnswer c -> {
                                probability(c.confidence());
                                c.probabilities().values().forEach(GuardedJevApi::probability);
                            }
                            case ScoreAnswer s -> {
                                if (!Double.isFinite(s.value())) {
                                    throw new IllegalArgumentException("Jev score must be finite");
                                }
                                probability(s.confidence());
                                s.probabilities().values().forEach(GuardedJevApi::probability);
                            }
                            case UnknownAnswer ignored ->
                                    logger.debug("Jev response contains an unknown answer type");
                            case null ->
                                    throw new IllegalArgumentException(
                                            "Jev answer must not be null");
                        }
                    }
                    return response;
                });
    }

    private static void probability(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(
                    "Jev probability must be finite and between zero and one");
        }
    }

    @Override
    public ResponseEntity<ListModelsResponse> listModels() {
        return guarded(
                MODELS_OPERATION,
                () -> {
                    var response = super.listModels();
                    if (response.getBody() == null) {
                        throw new IllegalArgumentException("Jev model response must have a body");
                    }
                    return response;
                });
    }

    private <T extends ResponseEntity<?>> T guarded(String operation, Supplier<T> call) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Jev request cancelled");
        }
        var observation =
                Observation.createNotStarted(OBSERVATION_NAME, registry)
                        .lowCardinalityKeyValue(TAG_OPERATION, operation)
                        .start();
        String outcome = FAILURE;
        String family = NO_STATUS;
        try (var scope = observation.openScope()) {
            T response = call.get();
            family = response.getStatusCode().value() / 100 + "xx";
            outcome = SUCCESS;
            return response;
        } catch (SafeHttpFailure failure) {
            family = failure.status() / 100 + "xx";
            throw failure;
        } catch (ResourceAccessException failure) {
            throw safeFailure(failure);
        } catch (RestClientException failure) {
            throw safeFailure(failure);
        } catch (RuntimeException ignored) {
            throw new TypeSafeException("Jev request or response invalid");
        } finally {
            observation
                    .lowCardinalityKeyValue(TAG_OUTCOME, outcome)
                    .lowCardinalityKeyValue(TAG_STATUS_FAMILY, family)
                    .stop();
            logger.debug(
                    "Jev operation={} outcome={} status.family={}", operation, outcome, family);
        }
    }

    private static TypeSafeException safeFailure(ResourceAccessException failure) {
        if (causedByTimeout(failure)) {
            return new TypeSafeApiTimeoutException("Jev transport timed out", null, null);
        }
        return new TypeSafeApiConnectionException("Jev connection failed", null);
    }

    private static TypeSafeException safeFailure(RestClientException failure) {
        if (causedByTimeout(failure)) {
            return new TypeSafeApiTimeoutException("Jev transport timed out", null, null);
        }
        return new TypeSafeException("Jev request or response invalid");
    }

    private static boolean causedByTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    // Extends the SDK exception so callers retain status without response content.
    @SuppressWarnings("java:S110")
    private static final class SafeHttpFailure extends TypeSafeApiException {
        @Serial private static final long serialVersionUID = 1L;

        SafeHttpFailure(int status) {
            super("Jev HTTP request failed", status, null, HttpHeaders.EMPTY, "");
        }
    }

    private static final class SafeErrors implements ResponseErrorHandler {
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return !response.getStatusCode().is2xxSuccessful();
        }

        @Override
        public void handleError(URI uri, HttpMethod method, ClientHttpResponse response)
                throws IOException {
            throw new SafeHttpFailure(response.getStatusCode().value());
        }
    }
}
