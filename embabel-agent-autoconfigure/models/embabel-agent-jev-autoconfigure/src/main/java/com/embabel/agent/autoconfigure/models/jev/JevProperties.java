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
package com.embabel.agent.autoconfigure.models.jev;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Configuration for the opt-in native Jev client, bound beneath {@value #PREFIX}.
 *
 * <p>Client creation validates these options only when Jev is enabled and no application {@code
 * TypeSafeClient} bean exists. A supplied HTTP transport retains ownership of its connection/read
 * timeouts, redirects, TLS and proxy settings.
 *
 * @param enabled whether to create a client; defaults to {@code false}
 * @param apiKey optional API credential; a nonblank value takes precedence over {@value
 *     #API_KEY_ENVIRONMENT_VARIABLE}. The environment fallback is resolved for each request. This
 *     value is excluded from {@link #toString()}.
 * @param model default model for requests without an explicit model; defaults to {@code jev-latest}
 *     and must be nonblank
 * @param baseUri API origin; defaults to {@code https://api.typesafe.ai}. HTTPS is required except
 *     for loopback HTTP. User information, query, fragment and non-root paths are rejected.
 * @param connectTimeout fallback transport connection timeout; defaults to 10 seconds and must be
 *     between 1 millisecond and {@link Integer#MAX_VALUE} milliseconds
 * @param readTimeout fallback transport read timeout; defaults to 10 seconds with the same bounds
 *     as {@code connectTimeout}. Neither timeout is a total request deadline.
 * @param maxResponseBytes positive maximum response body size in bytes; defaults to 1 MiB
 */
@ConfigurationProperties(JevProperties.PREFIX)
public record JevProperties(
        @DefaultValue("false") boolean enabled,
        String apiKey,
        @DefaultValue("jev-latest") String model,
        @DefaultValue("https://api.typesafe.ai") URI baseUri,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("1048576") int maxResponseBytes) {

    /** Spring property namespace for the native Jev client. */
    public static final String PREFIX = "embabel.agent.platform.models.jev";

    /** Environment property used when no nonblank API key is configured. */
    public static final String API_KEY_ENVIRONMENT_VARIABLE = "TYPESAFE_API_KEY";

    /**
     * Returns the enablement state without credentials or other configured values.
     *
     * @return a credential-redacted diagnostic representation
     */
    @Override
    public String toString() {
        return "JevProperties[enabled=" + enabled + ", apiKey=[REDACTED]]";
    }
}
