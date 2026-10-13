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

import static com.embabel.common.byok.BlankApiKeyKt.requireUsableApiKey;

import com.embabel.agent.systemone.SystemOneModelFactory;

import io.micrometer.observation.ObservationRegistry;

import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds decision services for TypeSafe's Jev models. TypeSafe speaks System One, so this is a
 * {@link SystemOneModelFactory} with TypeSafe's address, provider name and request id header.
 *
 * <p>TypeSafe always needs a key. A missing key fails each call as unavailable before anything is
 * sent, and {@link #buildValidated()} rejects it. Building a service never reads the key.
 */
@ApiStatus.Experimental
public class TypeSafeModelFactory extends SystemOneModelFactory {

    /** The provider name on every service and result this factory produces. */
    public static final String PROVIDER = "TypeSafe";

    /** The Jev alias that always points at TypeSafe's newest model. */
    public static final String DEFAULT_MODEL = "jev-latest";

    /** The response header TypeSafe puts its request id in. */
    public static final String REQUEST_ID_HEADER = "x-typesafe-request-id";

    /**
     * Uses TypeSafe's address and the default model.
     *
     * @param apiKey supplies the key for each call
     */
    public TypeSafeModelFactory(Supplier<String> apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    /**
     * Uses TypeSafe's address with another default model.
     *
     * @param apiKey supplies the key for each call
     * @param defaultModel the model {@link #build()} and {@link #buildValidated()} return
     */
    public TypeSafeModelFactory(Supplier<String> apiKey, String defaultModel) {
        this(TypeSafeClientOptions.defaults(), apiKey, null, ObservationRegistry.NOOP, defaultModel);
    }

    /**
     * Uses the given settings and the default model.
     *
     * @param options where TypeSafe lives and how long to wait for it
     * @param apiKey supplies the key for each call
     */
    public TypeSafeModelFactory(TypeSafeClientOptions options, Supplier<String> apiKey) {
        this(options, apiKey, ObservationRegistry.NOOP);
    }

    /**
     * Uses the given settings and the default model, recording observations.
     *
     * @param options where TypeSafe lives and how long to wait for it
     * @param apiKey supplies the key for each call
     * @param observationRegistry records decision and request observations
     */
    public TypeSafeModelFactory(
            TypeSafeClientOptions options,
            Supplier<String> apiKey,
            ObservationRegistry observationRegistry) {
        this(options, apiKey, null, observationRegistry, DEFAULT_MODEL);
    }

    /**
     * Uses the given settings and the application's HTTP client.
     *
     * @param options where TypeSafe lives and how long to wait for it
     * @param apiKey supplies the key for each call
     * @param restClientBuilder the application's HTTP client builder, or null for the factory's own
     */
    public TypeSafeModelFactory(
            TypeSafeClientOptions options,
            Supplier<String> apiKey,
            RestClient.@Nullable Builder restClientBuilder) {
        this(options, apiKey, restClientBuilder, ObservationRegistry.NOOP);
    }

    /**
     * Uses the given settings and the application's HTTP client, recording observations.
     *
     * @param options where TypeSafe lives and how long to wait for it
     * @param apiKey supplies the key for each call
     * @param restClientBuilder the application's HTTP client builder, or null for the factory's own
     * @param observationRegistry records decision and request observations
     */
    public TypeSafeModelFactory(
            TypeSafeClientOptions options,
            Supplier<String> apiKey,
            RestClient.@Nullable Builder restClientBuilder,
            ObservationRegistry observationRegistry) {
        this(options, apiKey, restClientBuilder, observationRegistry, DEFAULT_MODEL);
    }

    /**
     * Sets every option.
     *
     * @param options where TypeSafe lives and how long to wait for it
     * @param apiKey supplies the key for each call
     * @param restClientBuilder the application's HTTP client builder, or null for the factory's own
     * @param observationRegistry records decision and request observations
     * @param defaultModel the model {@link #build()} and {@link #buildValidated()} return
     */
    public TypeSafeModelFactory(
            TypeSafeClientOptions options,
            Supplier<String> apiKey,
            RestClient.@Nullable Builder restClientBuilder,
            ObservationRegistry observationRegistry,
            String defaultModel) {
        super(
                Objects.requireNonNull(options, "options").endpoint(),
                Objects.requireNonNull(defaultModel, "defaultModel"),
                requiringKey(apiKey),
                restClientBuilder,
                Objects.requireNonNull(observationRegistry, "observationRegistry"));
    }

    /**
     * Wraps the key supplier so a missing or blank key fails the call before anything is sent.
     *
     * @param apiKey supplies the key for each call
     * @return a supplier that only returns a usable key
     */
    private static Supplier<String> requiringKey(Supplier<String> apiKey) {
        Objects.requireNonNull(apiKey, "apiKey");
        return () -> {
            var key = apiKey.get();
            requireUsableApiKey(key);
            return key;
        };
    }
}
