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

import java.net.URI;
import java.time.Duration;
import org.jetbrains.annotations.ApiStatus.Experimental;

/** Non-secret options. Timeouts apply to the fallback transport, not a global deadline. */
@Experimental
public record JevClientOptions(URI baseUri, String model, Duration connectTimeout,
                               Duration readTimeout, int maxResponseBytes) {
    public JevClientOptions {
        if (baseUri == null || baseUri.getHost() == null || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null || baseUri.getFragment() != null
                || !(baseUri.getPath().isEmpty() || baseUri.getPath().equals("/"))
                || !("https".equals(baseUri.getScheme()) || "http".equals(baseUri.getScheme())
                && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(baseUri.getHost()))) {
            throw new IllegalArgumentException("Jev base URI must be an HTTPS origin (HTTP allowed on loopback)");
        }
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Jev model must not be blank");
        validTimeout(connectTimeout);
        validTimeout(readTimeout);
        if (maxResponseBytes < 1) throw new IllegalArgumentException("Jev response byte limit must be positive");
    }

    private static void validTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0 || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("Jev timeout must be between 1ms and 2147483647ms");
        }
    }

    public static JevClientOptions defaults() {
        return new JevClientOptions(URI.create("https://api.typesafe.ai"), "jev-latest",
                Duration.ofSeconds(10), Duration.ofSeconds(10), 1024 * 1024);
    }
}
