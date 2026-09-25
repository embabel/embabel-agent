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

import org.jetbrains.annotations.ApiStatus.Experimental;
import org.springframework.util.Assert;

import java.net.URI;
import java.time.Duration;

/**
 * Non-secret settings for a synchronous native TypeSafe client.
 *
 * <p>Timeouts configure only the fallback transport. A supplied transport owns its timeouts;
 * neither configuration establishes a global request deadline.
 *
 * @param baseUri absolute HTTP(S) base URI without user information, query or fragment; defaults to
 *     the TypeSafe HTTPS endpoint
 * @param model nonblank default model, supplied explicitly to the SDK without environment fallback
 * @param connectTimeout fallback connection timeout, from one millisecond to Integer.MAX_VALUE
 *     milliseconds
 * @param readTimeout fallback socket read timeout, with the same limits as connectTimeout
 * @param maxResponseBytes positive byte limit for TypeSafe response decoding; application-owned
 *     buffering/interceptors may read before this limit applies
 */
@Experimental
public record TypeSafeClientOptions(
        URI baseUri,
        String model,
        Duration connectTimeout,
        Duration readTimeout,
        int maxResponseBytes) {
    private static final URI DEFAULT_BASE_URI = URI.create("https://api.typesafe.ai");
    private static final String DEFAULT_MODEL = "jev-latest";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_RESPONSE_BYTES = 1024 * 1024;
    private static final Duration MIN_TIMEOUT = Duration.ofMillis(1);
    private static final Duration MAX_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    /**
     * Validates settings without resolving credentials or opening a connection.
     *
     * @throws IllegalArgumentException if the origin, model, timeout or response limit is invalid
     */
    public TypeSafeClientOptions {
        validateBaseUri(baseUri);
        Assert.hasText(model, "TypeSafe model must not be blank");
        validateTimeout(connectTimeout);
        validateTimeout(readTimeout);
        Assert.isTrue(maxResponseBytes > 0, "TypeSafe response byte limit must be positive");
    }

    /**
     * Accepts configured HTTP endpoints, including proxy base paths, without resolving a host.
     * Credentials belong in the request header so URI diagnostics cannot expose them.
     */
    private static void validateBaseUri(URI uri) {
        Assert.notNull(uri, "TypeSafe base URI is required");
        Assert.isTrue(
                uri.getHost() != null
                        && ("https".equalsIgnoreCase(uri.getScheme())
                                || "http".equalsIgnoreCase(uri.getScheme()))
                        && uri.getUserInfo() == null
                        && uri.getQuery() == null
                        && uri.getFragment() == null,
                "TypeSafe base URI must be HTTP(S) without credentials, query or fragment");
    }

    /** Keeps fallback timeouts within the finite millisecond range supported by URLConnection. */
    private static void validateTimeout(Duration timeout) {
        Assert.notNull(timeout, "TypeSafe timeout is required");
        Assert.isTrue(
                timeout.compareTo(MIN_TIMEOUT) >= 0 && timeout.compareTo(MAX_TIMEOUT) <= 0,
                "TypeSafe timeout must be between 1ms and 2147483647ms");
    }

    /**
     * Returns defaults targeting https://api.typesafe.ai with model jev-latest, ten-second fallback
     * connect/read timeouts and a one-MiB response decoding limit.
     *
     * @return immutable defaults without a credential
     */
    public static TypeSafeClientOptions defaults() {
        return new TypeSafeClientOptions(
                DEFAULT_BASE_URI,
                DEFAULT_MODEL,
                DEFAULT_TIMEOUT,
                DEFAULT_TIMEOUT,
                DEFAULT_RESPONSE_BYTES);
    }
}
