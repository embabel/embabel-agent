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

import com.embabel.agent.jev.internal.GuardedJevApi;

import io.micrometer.observation.ObservationRegistry;

import org.jetbrains.annotations.ApiStatus.Experimental;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Creates native synchronous SDK clients with strict response decoding and sanitized failures.
 *
 * <p>SDK retries are disabled. Native batch operations are unsupported by this integration. Callers
 * own input mutation during synchronous calls; SDK response types are not persistence DTOs.
 */
@Experimental
public final class JevClients {
    private JevClients() {}

    /**
     * Creates a client with the fallback transport and no observation handlers.
     *
     * @param options validated non-secret settings
     * @param keySupplier credential source, evaluated for each request to support rotation
     * @return native client using finite JDK URLConnection timeouts and disabled redirects
     * @throws NullPointerException if options or keySupplier is null
     */
    public static TypeSafeClient create(JevClientOptions options, Supplier<String> keySupplier) {
        return create(options, keySupplier, null, ObservationRegistry.NOOP);
    }

    /**
     * Creates a client from a cloned builder, without Jev-owned observation handlers.
     *
     * @param options validated settings; fallback timeouts apply only when builder is null
     * @param keySupplier credential source, evaluated for each request
     * @param builder application builder, or null to select the fallback transport
     * @return native client retaining a supplied builder's HTTP observations and transport
     * @throws NullPointerException if options or keySupplier is null
     * @see #create(JevClientOptions, Supplier, RestClient.Builder, ObservationRegistry)
     */
    public static TypeSafeClient create(
            JevClientOptions options,
            Supplier<String> keySupplier,
            RestClient.@Nullable Builder builder) {
        return create(options, keySupplier, builder, ObservationRegistry.NOOP);
    }

    /**
     * Creates a client with one logical Jev observation per API invocation.
     *
     * <p>A supplied builder is cloned, retaining its transport, interceptors, TLS, proxy and HTTP
     * observation registry. Its owner controls timeouts and redirects. A null builder selects
     * Spring's JDK URLConnection transport with finite timeouts, disabled redirects and HTTP
     * observations using registry. HTTP observations retain Spring's application-owned tags; only
     * Jev-owned observations guarantee fixed operation, outcome and status-family tags.
     *
     * <p>The private strict Jackson mapper does not inherit application modules. Convert custom
     * state to native JsonContent or Map before calling if application serialization is needed.
     * Response limits apply to Jev decoding; application buffering can occur before that boundary.
     * Credentials and transport/decoding failures are sanitized at the API boundary. A missing or
     * blank credential fails when the request runs, without exposing the supplier's message.
     *
     * @param options validated non-secret settings
     * @param keySupplier credential source evaluated per request; never retained in options
     * @param builder application builder to clone, or null for the fallback transport
     * @param registry registry for logical Jev observations and fallback HTTP observations; use
     *     ObservationRegistry.NOOP to disable those observations
     * @return native synchronous client with retries disabled
     * @throws NullPointerException if options, keySupplier or registry is null
     */
    public static TypeSafeClient create(
            JevClientOptions options,
            Supplier<String> keySupplier,
            RestClient.@Nullable Builder builder,
            ObservationRegistry registry) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(keySupplier, "keySupplier");
        Objects.requireNonNull(registry, "registry");
        return new TypeSafeClient(
                new GuardedJevApi(options, keySupplier, builder, registry),
                options.model(),
                RetryPolicy.noRetry(),
                null);
    }
}
