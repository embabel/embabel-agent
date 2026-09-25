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

import org.apache.commons.lang3.Range;
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
 * @param baseUri non-null provider base URI; endpoint validation belongs to the HTTP client
 * @param connectTimeout fallback connection timeout, from one millisecond to Integer.MAX_VALUE
 *     milliseconds
 * @param readTimeout fallback socket read timeout, with the same limits as connectTimeout
 * @param maxResponseBytes positive byte limit for TypeSafe response decoding; application-owned
 *     buffering/interceptors may read before this limit applies
 */
@Experimental
public record TypeSafeClientOptions(
        URI baseUri, Duration connectTimeout, Duration readTimeout, int maxResponseBytes) {
    private static final URI DEFAULT_BASE_URI = URI.create("https://api.typesafe.ai");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_RESPONSE_BYTES = 1024 * 1024;
    private static final Range<Duration> TIMEOUT_RANGE =
            Range.of(Duration.ofMillis(1), Duration.ofMillis(Integer.MAX_VALUE));

    /**
     * Validates settings without resolving credentials or opening a connection.
     *
     * @throws IllegalArgumentException if the URI is null or a timeout or response limit is invalid
     */
    public TypeSafeClientOptions {
        Assert.notNull(baseUri, "TypeSafe base URI is required");
        Assert.isTrue(
                baseUri.getUserInfo() == null
                        && (baseUri.getRawAuthority() == null
                                || !baseUri.getRawAuthority().contains("@"))
                        && baseUri.getRawQuery() == null
                        && baseUri.getRawFragment() == null,
                "TypeSafe base URI must not contain credentials, a query or a fragment");
        validateTimeout(connectTimeout);
        validateTimeout(readTimeout);
        Assert.isTrue(maxResponseBytes > 0, "TypeSafe response byte limit must be positive");
    }

    /** Keeps fallback timeouts within the finite millisecond range supported by URLConnection. */
    private static void validateTimeout(Duration timeout) {
        Assert.isTrue(
                TIMEOUT_RANGE.contains(timeout),
                "TypeSafe timeout must be between 1ms and 2147483647ms");
    }

    /**
     * Returns defaults targeting https://api.typesafe.ai with ten-second fallback connect/read
     * timeouts and a one-MiB response decoding limit.
     *
     * @return immutable defaults without a credential
     */
    public static TypeSafeClientOptions defaults() {
        return new TypeSafeClientOptions(
                DEFAULT_BASE_URI, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT, DEFAULT_RESPONSE_BYTES);
    }

    /**
     * Keeps credentials, query parameters and gateway paths out of diagnostics.
     *
     * @return a representation containing only safe transport bounds
     */
    @Override
    public String toString() {
        return "TypeSafeClientOptions[baseUri=[CONFIGURED], connectTimeout=%s, readTimeout=%s, maxResponseBytes=%d]"
                .formatted(connectTimeout, readTimeout, maxResponseBytes);
    }
}
