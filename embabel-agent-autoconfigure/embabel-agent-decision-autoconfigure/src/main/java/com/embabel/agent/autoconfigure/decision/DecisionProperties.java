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
package com.embabel.agent.autoconfigure.decision;

import org.jetbrains.annotations.ApiStatus;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Configuration for named experimental typed decision models. */
@ApiStatus.Experimental
public record DecisionProperties(
        boolean enabled,
        Duration defaultTimeout,
        String recordMode,
        int fullRecordMaxBytes,
        Set<String> recordAllowlist,
        String mapperBeanName,
        Map<String, Model> models) {

    public DecisionProperties {
        recordAllowlist = recordAllowlist == null ? Set.of() : Set.copyOf(recordAllowlist);
        models = models == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(models));
    }

    @Override public String toString() { return "DecisionProperties[redacted]"; }

    /** One configured decision model. The enclosing map key is its Spring and registry name. */
    @ApiStatus.Experimental
    public record Model(String provider, Typesafe typesafe, Prompted prompted) {
        @Override public String toString() { return "DecisionProperties.Model[redacted]"; }
    }

    /** TypeSafe System One settings for one named registry facade. */
    @ApiStatus.Experimental
    public record Typesafe(
            String model,
            @DefaultValue(Typesafe.DEFAULT_BASE_URL) URI baseUrl,
            @DefaultValue(Typesafe.DEFAULT_CONNECT_TIMEOUT) Duration connectTimeout) {
        private static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
        private static final String DEFAULT_CONNECT_TIMEOUT = "PT10S";

        static Typesafe defaults(String model) {
            return new Typesafe(model, URI.create(DEFAULT_BASE_URL), Duration.parse(DEFAULT_CONNECT_TIMEOUT));
        }

        @Override public String toString() { return "DecisionProperties.Typesafe[redacted]"; }
    }

    /** Prompted decision settings for one named registry facade. */
    @ApiStatus.Experimental
    public record Prompted(String llmBeanName, String optionsBeanName) {
        @Override public String toString() { return "DecisionProperties.Prompted[redacted]"; }
    }
}
