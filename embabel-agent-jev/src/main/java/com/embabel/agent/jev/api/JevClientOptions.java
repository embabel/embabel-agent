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

import org.jetbrains.annotations.ApiStatus.Experimental;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * Non-secret settings for a synchronous native Jev client.
 *
 * <p>Timeouts configure only the fallback transport. A supplied transport owns its timeouts;
 * neither configuration establishes a global request deadline.
 *
 * @param baseUri HTTPS origin without user information, query, fragment or non-root path; HTTP is
 *     allowed only for localhost, 127.0.0.1 and ::1
 * @param model nonblank default model, supplied explicitly to the SDK without environment fallback
 * @param connectTimeout fallback connection timeout, from one millisecond to Integer.MAX_VALUE
 *     milliseconds
 * @param readTimeout fallback socket read timeout, with the same limits as connectTimeout
 * @param maxResponseBytes positive byte limit for Jev response decoding; application-owned
 *     buffering/interceptors may read before this limit applies
 */
@Experimental
public record JevClientOptions(
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
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    /**
     * Validates settings without resolving credentials or opening a connection.
     *
     * @throws IllegalArgumentException if the origin, model, timeout or response limit is invalid
     */
    public JevClientOptions {
        validateOrigin(baseUri);
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Jev model must not be blank");
        }
        validateTimeout(connectTimeout);
        validateTimeout(readTimeout);
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("Jev response byte limit must be positive");
        }
    }

    private static void validateOrigin(URI uri) {
        if (uri == null
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw invalidOrigin();
        }
        boolean rootPath = uri.getPath().isEmpty() || uri.getPath().equals("/");
        boolean allowedScheme =
                "https".equals(uri.getScheme())
                        || "http".equals(uri.getScheme()) && LOOPBACK_HOSTS.contains(uri.getHost());
        if (!rootPath || !allowedScheme) {
            throw invalidOrigin();
        }
    }

    private static IllegalArgumentException invalidOrigin() {
        return new IllegalArgumentException(
                "Jev base URI must be an HTTPS origin (HTTP allowed on loopback)");
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout == null
                || timeout.compareTo(MIN_TIMEOUT) < 0
                || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("Jev timeout must be between 1ms and 2147483647ms");
        }
    }

    /**
     * Returns defaults targeting https://api.typesafe.ai with model jev-latest, ten-second fallback
     * connect/read timeouts and a one-MiB response decoding limit.
     *
     * @return immutable defaults without a credential
     */
    public static JevClientOptions defaults() {
        return new JevClientOptions(
                DEFAULT_BASE_URI,
                DEFAULT_MODEL,
                DEFAULT_TIMEOUT,
                DEFAULT_TIMEOUT,
                DEFAULT_RESPONSE_BYTES);
    }
}
