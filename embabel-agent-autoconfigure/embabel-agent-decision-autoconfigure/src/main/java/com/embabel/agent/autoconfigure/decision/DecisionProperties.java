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

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Configuration for named experimental typed decision models. */
@ApiStatus.Experimental
public final class DecisionProperties {
    private boolean enabled;
    private Duration defaultTimeout = Duration.ofSeconds(30);
    private String recordMode = "metadata";
    private int fullRecordMaxBytes = 65536;
    private Set<String> recordAllowlist = new LinkedHashSet<>();
    private String mapperBeanName;
    private Map<String, Model> models = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getDefaultTimeout() { return defaultTimeout; }
    public void setDefaultTimeout(Duration defaultTimeout) { this.defaultTimeout = defaultTimeout; }
    public String getRecordMode() { return recordMode; }
    public void setRecordMode(String recordMode) { this.recordMode = recordMode; }
    public int getFullRecordMaxBytes() { return fullRecordMaxBytes; }
    public void setFullRecordMaxBytes(int fullRecordMaxBytes) { this.fullRecordMaxBytes = fullRecordMaxBytes; }
    public Set<String> getRecordAllowlist() { return Set.copyOf(recordAllowlist); }
    public void setRecordAllowlist(Set<String> recordAllowlist) {
        this.recordAllowlist = recordAllowlist == null ? new LinkedHashSet<>() : new LinkedHashSet<>(recordAllowlist);
    }
    public String getMapperBeanName() { return mapperBeanName; }
    public void setMapperBeanName(String mapperBeanName) { this.mapperBeanName = mapperBeanName; }
    public Map<String, Model> getModels() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(models));
    }
    public void setModels(Map<String, Model> models) {
        this.models = models == null ? new LinkedHashMap<>() : new LinkedHashMap<>(models);
    }

    void addModel(String name, Model model) { models.put(name, model); }

    @Override public String toString() { return "DecisionProperties[redacted]"; }

    /** One configured decision model. The enclosing map key is its Spring and registry name. */
    @ApiStatus.Experimental
    public static final class Model {
        private String provider;
        private Typesafe typesafe;
        private Prompted prompted;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public Typesafe typesafe() { return typesafe; }
        public Prompted prompted() { return prompted; }

        void select(Typesafe typesafe) { this.typesafe = typesafe; }
        void select(Prompted prompted) { this.prompted = prompted; }

        @Override public String toString() { return "DecisionProperties.Model[redacted]"; }
    }

    /** TypeSafe System One settings for one named registry facade. */
    @ApiStatus.Experimental
    public static final class Typesafe {
        private String model;
        private URI baseUrl = URI.create("https://api.typesafe.ai");
        private Duration connectTimeout = Duration.ofSeconds(10);

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public URI getBaseUrl() { return baseUrl; }
        public void setBaseUrl(URI baseUrl) { this.baseUrl = baseUrl; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        @Override public String toString() { return "DecisionProperties.Typesafe[redacted]"; }
    }

    /** Prompted decision settings for one named registry facade. */
    @ApiStatus.Experimental
    public static final class Prompted {
        private String llmBeanName;
        private String optionsBeanName;

        public String getLlmBeanName() { return llmBeanName; }
        public void setLlmBeanName(String llmBeanName) { this.llmBeanName = llmBeanName; }
        public String getOptionsBeanName() { return optionsBeanName; }
        public void setOptionsBeanName(String optionsBeanName) { this.optionsBeanName = optionsBeanName; }
        @Override public String toString() { return "DecisionProperties.Prompted[redacted]"; }
    }
}
