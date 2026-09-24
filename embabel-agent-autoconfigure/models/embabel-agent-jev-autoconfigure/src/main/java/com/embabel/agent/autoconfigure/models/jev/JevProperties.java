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

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuration for the opt-in native Jev client. Timeouts apply to its fallback transport. */
@ConfigurationProperties("embabel.agent.platform.models.jev")
public record JevProperties(
        @DefaultValue("false") boolean enabled,
        String apiKey,
        @DefaultValue("jev-latest") String model,
        @DefaultValue("https://api.typesafe.ai") URI baseUri,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("1048576") int maxResponseBytes) {

    @Override
    public String toString() {
        return "JevProperties[enabled=" + enabled + ", apiKey=[REDACTED]]";
    }
}
