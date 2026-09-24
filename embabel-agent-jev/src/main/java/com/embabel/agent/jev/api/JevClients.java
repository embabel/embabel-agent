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
import java.util.Objects;
import java.util.function.Supplier;
import org.jetbrains.annotations.ApiStatus.Experimental;
import org.springframework.web.client.RestClient;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;

/** Creates native synchronous clients. SDK batch operations are unsupported. */
@Experimental
public final class JevClients {
    private JevClients() { }

    public static TypeSafeClient create(JevClientOptions options, Supplier<String> keySupplier) {
        return create(options, keySupplier, null, ObservationRegistry.NOOP);
    }

    public static TypeSafeClient create(JevClientOptions options, Supplier<String> keySupplier,
                                        RestClient.Builder builder) {
        return create(options, keySupplier, builder, ObservationRegistry.NOOP);
    }

    /**
     * Clones a supplied builder, preserving transport, interceptors and HTTP observations.
     * Its owner controls timeouts, redirects, TLS and proxy configuration. A null builder
     * selects the finite-timeout JDK transport with redirects disabled. The private protocol
     * mapper does not inherit application modules. Callers own synchronous input mutation.
     */
    public static TypeSafeClient create(JevClientOptions options, Supplier<String> keySupplier,
                                        RestClient.Builder builder, ObservationRegistry registry) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(keySupplier, "keySupplier");
        Objects.requireNonNull(registry, "registry");
        return new TypeSafeClient(new GuardedJevApi(options, keySupplier, builder, registry),
                options.model(), RetryPolicy.noRetry(), null);
    }
}
