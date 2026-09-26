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

import com.embabel.agent.typesafe.internal.GuardedTypeSafeApi;

import io.micrometer.observation.ObservationRegistry;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds native TypeSafe clients for selected models from shared provider configuration.
 *
 * <p>Usable without a Spring context. Credentials are resolved for each request. Clients share the
 * guarded transport and observations, while each retains its own default model. Construction does
 * not call the provider or resolve credentials.
 *
 * <p>This factory returns SDK clients. The decision-model layer owns Embabel model metadata,
 * classification and decision contracts. SDK retries are disabled and batch helpers are
 * unsupported.
 */
final class TypeSafeClientFactory {

    private final GuardedTypeSafeApi api;

    /**
     * Uses the provider defaults and fallback transport without observation handlers.
     *
     * @param keySupplier credential source evaluated for each request
     */
    TypeSafeClientFactory(Supplier<String> keySupplier) {
        this(TypeSafeClientOptions.defaults(), keySupplier);
    }

    /**
     * Uses configured transport bounds without observation handlers.
     *
     * @param options non-secret provider settings
     * @param keySupplier credential source evaluated for each request
     */
    TypeSafeClientFactory(TypeSafeClientOptions options, Supplier<String> keySupplier) {
        this(options, keySupplier, null, ObservationRegistry.NOOP);
    }

    /**
     * Preserves application HTTP observations without adding logical observation handlers.
     *
     * @param options non-secret provider settings
     * @param keySupplier credential source evaluated for each request
     * @param builder application builder to clone, or null for the fallback transport
     */
    TypeSafeClientFactory(
            TypeSafeClientOptions options,
            Supplier<String> keySupplier,
            RestClient.@Nullable Builder builder) {
        this(options, keySupplier, builder, ObservationRegistry.NOOP);
    }

    /**
     * Configures one guarded API shared by the clients this factory builds.
     *
     * <p>A supplied builder is cloned and retains its transport, interceptors and HTTP observation
     * registry. Its owner controls timeouts and redirects. With no builder, the fallback uses
     * finite URLConnection timeouts, disables redirects and installs HTTP observations on the
     * supplied registry. The registry also controls logical TypeSafe observations. Only those
     * logical observations guarantee fixed operation, outcome and status-family tags; application
     * HTTP instrumentation owns its tags.
     *
     * <p>The private strict mapper does not inherit application Jackson modules. Convert custom
     * state to JSON-compatible values before calling. Response bounds apply during TypeSafe
     * decoding; application buffering can occur earlier. Raw credential, transport and decoding
     * failures stay inside the guard.
     *
     * @param options non-secret settings; timeouts apply only to the fallback transport
     * @param keySupplier credential source evaluated for each request
     * @param builder application builder to clone, or null for the fallback transport
     * @param registry registry for logical observations and fallback HTTP observations
     * @throws NullPointerException if options, keySupplier or registry is null
     */
    TypeSafeClientFactory(
            TypeSafeClientOptions options,
            Supplier<String> keySupplier,
            RestClient.@Nullable Builder builder,
            ObservationRegistry registry) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(keySupplier, "keySupplier");
        Objects.requireNonNull(registry, "registry");
        this.api = new GuardedTypeSafeApi(options, keySupplier, builder, registry);
    }

    /**
     * Builds a client using Jev as its default model.
     *
     * @return a native client with default model {@code jev-latest}
     */
    TypeSafeClient build() {
        return build(TypeSafeModelFactory.DEFAULT_MODEL);
    }

    /**
     * Builds a client with an independent default model and the factory's shared provider settings.
     * Explicit request models override this default. No credential validation call is made.
     *
     * @param model nonblank default model identifier
     * @return a native synchronous client with retries disabled
     * @throws IllegalArgumentException if model is null or blank
     */
    TypeSafeClient build(String model) {
        Assert.hasText(model, "TypeSafe model must not be blank");
        return new TypeSafeClient(api, model, RetryPolicy.noRetry(), null);
    }

    /** Validates provider access without exposing the native client outside this package. */
    void validate(String model) {
        build(model).listModels();
    }
}
