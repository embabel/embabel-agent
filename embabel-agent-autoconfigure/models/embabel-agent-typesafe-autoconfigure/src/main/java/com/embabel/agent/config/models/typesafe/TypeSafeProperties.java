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
package com.embabel.agent.config.models.typesafe;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the native TypeSafe client, bound beneath {@value #PREFIX}.
 *
 * <p>An application-provided {@code TypeSafeClient} skips this configuration. HTTP transport
 * settings belong to the shared or application-provided {@code RestClient.Builder}.
 *
 * @param apiKey API credential used when {@code TYPESAFE_API_KEY} is absent or blank; excluded from
 *     {@link #toString()}. The environment key is resolved for each request.
 * @param baseUrl HTTP(S) provider endpoint; defaults to {@code https://api.typesafe.ai}. Proxy base
 *     paths are supported. Credentials, query parameters and fragments are rejected.
 * @param model default model for requests without an explicit model; defaults to {@code jev-latest}
 *     and must be nonblank
 * @param maxResponseBytes positive maximum response body size in bytes; defaults to 1 MiB
 */
@ConfigurationProperties(TypeSafeProperties.PREFIX)
public record TypeSafeProperties(
        String apiKey,
        @DefaultValue("https://api.typesafe.ai") String baseUrl,
        @DefaultValue("jev-latest") String model,
        @DefaultValue("1048576") int maxResponseBytes) {

    /** Spring property namespace for the native TypeSafe client. */
    public static final String PREFIX = "embabel.agent.platform.models.typesafe";

    /**
     * Keeps credentials out of configuration diagnostics.
     *
     * @return a credential-redacted diagnostic representation
     */
    @Override
    public String toString() {
        return "TypeSafeProperties[apiKey=[REDACTED]]";
    }
}
