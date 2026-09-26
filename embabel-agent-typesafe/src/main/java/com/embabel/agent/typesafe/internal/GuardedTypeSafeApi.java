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
package com.embabel.agent.typesafe.internal;

import com.embabel.agent.typesafe.TypeSafeClientOptions;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.jetbrains.annotations.ApiStatus.Internal;
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
import org.springframework.http.HttpStatusCode;
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
import java.io.InputStream;
import java.io.Serial;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/** Internal boundary: unsafe transport failures never reach SDK retry diagnostics. */
@Internal
public final class GuardedTypeSafeApi extends TypeSafeApi {
    private static final Logger logger = LoggerFactory.getLogger(GuardedTypeSafeApi.class);
    private static final String OBSERVATION_NAME = "embabel.typesafe.request";
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

    public GuardedTypeSafeApi(
            TypeSafeClientOptions options,
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
                        throw new IllegalArgumentException("TypeSafe credential unavailable");
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

    /**
     * Preserves a supplied transport while installing the provider's decoding and response limits.
     * The private mapper keeps application modules from changing the TypeSafe wire contract and
     * avoids a dependency on the agent platform's mapper holder.
     */
    private static RestClient.Builder configure(
            TypeSafeClientOptions options,
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
                                0,
                                (request, body, execution) ->
                                        new BoundedResponse(
                                                execution.execute(request, body),
                                                options.maxResponseBytes())));
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
                        throw new IllegalArgumentException(
                                "TypeSafe response must contain answers");
                    }
                    for (Answer answer : response.getBody().answers().values()) {
                        switch (answer) {
                            case NoulAnswer(double value) -> probability(value);
                            case ChoiceAnswer c -> {
                                probability(c.confidence());
                                c.probabilities().values().forEach(GuardedTypeSafeApi::probability);
                            }
                            case ScoreAnswer s -> {
                                if (!Double.isFinite(s.value())) {
                                    throw new IllegalArgumentException(
                                            "TypeSafe score must be finite");
                                }
                                probability(s.confidence());
                                s.probabilities().values().forEach(GuardedTypeSafeApi::probability);
                            }
                            case UnknownAnswer ignored ->
                                    logger.debug(
                                            "TypeSafe response contains an unknown answer type");
                            case null ->
                                    throw new IllegalArgumentException(
                                            "TypeSafe answer must not be null");
                        }
                    }
                    return response;
                });
    }

    /**
     * Rejects missing, non-finite and out-of-range confidence values before returning SDK answers.
     */
    private static void probability(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(
                    "TypeSafe probability must be finite and between zero and one");
        }
    }

    @Override
    public ResponseEntity<ListModelsResponse> listModels() {
        return guarded(
                MODELS_OPERATION,
                () -> {
                    var response = super.listModels();
                    if (response.getBody() == null) {
                        throw new IllegalArgumentException(
                                "TypeSafe model response must have a body");
                    }
                    return response;
                });
    }

    /**
     * Records one logical operation and preserves safe HTTP status and timeout classifications. Raw
     * failures stay inside this boundary because their messages may contain credentials or state.
     */
    private <T extends ResponseEntity<?>> T guarded(String operation, Supplier<T> call) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("TypeSafe request cancelled");
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
            // Transport causes can contain credentials; retain only the safe failure classification.
            throw safeFailure(failure);
        } catch (RestClientException failure) {
            // Decoder causes can contain response content; do not retain the raw exception.
            throw safeFailure(failure);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException ignored) {
            // Request or response errors can contain credentials; do not wrap the raw exception.
            throw new TypeSafeException("TypeSafe request or response invalid");
        } finally {
            observation
                    .lowCardinalityKeyValue(TAG_OUTCOME, outcome)
                    .lowCardinalityKeyValue(TAG_STATUS_FAMILY, family)
                    .stop();
            logger.debug(
                    "TypeSafe operation={} outcome={} status.family={}",
                    operation,
                    outcome,
                    family);
        }
    }

    /** Preserves transport timeout classification without retaining the original failure. */
    private static TypeSafeException safeFailure(ResourceAccessException failure) {
        if (causedByTimeout(failure)) {
            return new TypeSafeApiTimeoutException("TypeSafe transport timed out", null, null);
        }
        return new TypeSafeApiConnectionException("TypeSafe connection failed", null);
    }

    /**
     * Removes response content from decoding failures while preserving nested timeout
     * classification.
     */
    private static TypeSafeException safeFailure(RestClientException failure) {
        if (causedByTimeout(failure)) {
            return new TypeSafeApiTimeoutException("TypeSafe transport timed out", null, null);
        }
        return new TypeSafeException("TypeSafe request or response invalid");
    }

    /** Recognizes both supported HTTP timeout types, including failures wrapped by Spring. */
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
            super("TypeSafe HTTP request failed", status, null, HttpHeaders.EMPTY, "");
        }
    }

    /** Streaming limit for both declared and chunked response bodies. */
    private static final class BoundedResponse implements ClientHttpResponse {
        private final ClientHttpResponse response;
        private final int maximumBytes;
        private InputStream limited;

        private BoundedResponse(ClientHttpResponse response, int maximumBytes) {
            this.response = response;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return response.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return response.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return response.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            if (limited == null) {
                if (response.getHeaders().getContentLength() > maximumBytes) {
                    throw exceeded();
                }
                limited = bounded(response.getBody(), maximumBytes);
            }
            return limited;
        }

        @Override
        public void close() {
            // Close the raw stream first so URLConnection cannot drain a rejected body.
            try {
                closeBody();
            } finally {
                closeResponse();
            }
        }

        /** Contains transport cleanup failures so they cannot replace the request outcome. */
        private void closeResponse() {
            try {
                response.close();
            } catch (RuntimeException ignored) {
                // Cleanup must not replace the result, and its exception may contain response content.
                logger.debug("TypeSafe response cleanup failed");
            }
        }

        /**
         * Closes the raw stream before transport cleanup can drain a rejected response. Opening the
         * body here is required when status or content length caused early rejection.
         */
        private void closeBody() {
            try {
                InputStream body = limited;
                if (body == null) {
                    body = response.getBody();
                }
                body.close();
            } catch (IOException | RuntimeException ignored) {
                // Keep the original decoding failure; cleanup exceptions may contain response content.
                logger.debug("TypeSafe response body cleanup failed");
            }
        }

        /**
         * Reads at most one byte beyond the limit to detect oversized chunked bodies. Delegating
         * close releases the underlying connection even after decoding has failed.
         */
        private static InputStream bounded(InputStream source, int maximumBytes) {
            return new InputStream() {
                private long remaining = maximumBytes;

                @Override
                public int read() throws IOException {
                    int value = source.read();
                    if (value != -1 && --remaining < 0) {
                        throw exceeded();
                    }
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    Objects.checkFromIndexSize(offset, length, bytes.length);
                    if (length == 0) {
                        return 0;
                    }
                    int count = source.read(bytes, offset, (int) Math.min(length, remaining + 1));
                    if (count > 0) {
                        remaining -= count;
                        if (remaining < 0) {
                            throw exceeded();
                        }
                    }
                    return count;
                }

                @Override
                public void close() throws IOException {
                    source.close();
                }
            };
        }

        private static IOException exceeded() {
            return new IOException("TypeSafe response exceeds configured byte limit");
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
