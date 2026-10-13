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

import com.embabel.agent.systemone.SystemOneEndpoint;

import org.jetbrains.annotations.ApiStatus.Experimental;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Where TypeSafe lives and how long to wait for it. None of these settings are secret.
 *
 * <p>The timeouts apply when the factory builds its own HTTP client. An application client keeps
 * its own timeouts. The response limit applies either way.
 *
 * @param baseUri the TypeSafe address, without user info, a query or a fragment
 * @param connectTimeout how long to wait for a connection, from 1 ms to {@link Integer#MAX_VALUE} ms
 * @param readTimeout how long to wait for data once connected, with the same limits
 * @param maxResponseBytes the largest response body that will be read
 */
@Experimental
public record TypeSafeClientOptions(
        URI baseUri, Duration connectTimeout, Duration readTimeout, int maxResponseBytes) {

    private static final URI DEFAULT_BASE_URI = URI.create("https://api.typesafe.ai");

    /**
     * Checks the settings without resolving a key or opening a connection.
     *
     * @throws IllegalArgumentException if the address, a timeout or the response limit is unusable
     */
    public TypeSafeClientOptions {
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        endpoint(baseUri, connectTimeout, readTimeout, maxResponseBytes);
    }

    /**
     * Returns the settings for {@code https://api.typesafe.ai}, with 10 second timeouts and a
     * 1 MiB response limit.
     *
     * @return the default settings
     */
    public static TypeSafeClientOptions defaults() {
        return new TypeSafeClientOptions(
                DEFAULT_BASE_URI,
                SystemOneEndpoint.DEFAULT_TIMEOUT,
                SystemOneEndpoint.DEFAULT_TIMEOUT,
                SystemOneEndpoint.DEFAULT_MAX_RESPONSE_BYTES);
    }

    /** Returns these settings as a System One endpoint named TypeSafe. */
    SystemOneEndpoint endpoint() {
        return endpoint(baseUri, connectTimeout, readTimeout, maxResponseBytes);
    }

    private static SystemOneEndpoint endpoint(
            URI baseUri, Duration connectTimeout, Duration readTimeout, int maxResponseBytes) {
        return new SystemOneEndpoint(
                TypeSafeModelFactory.PROVIDER,
                baseUri,
                connectTimeout,
                readTimeout,
                maxResponseBytes,
                TypeSafeModelFactory.REQUEST_ID_HEADER);
    }

    /** Leaves the address out, since a gateway path can be private. */
    @Override
    public String toString() {
        return "TypeSafeClientOptions[baseUri=[CONFIGURED], connectTimeout=%s, readTimeout=%s, maxResponseBytes=%d]"
                .formatted(connectTimeout, readTimeout, maxResponseBytes);
    }
}
