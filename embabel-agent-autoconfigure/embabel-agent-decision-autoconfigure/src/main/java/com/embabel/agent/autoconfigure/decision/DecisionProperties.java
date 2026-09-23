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
        this.models = new LinkedHashMap<>();
        if (models != null) models.forEach(this::addModel);
    }

    void addModel(String name, Model model) { models.put(name, model.immutableCopy()); }

    @Override public String toString() { return "DecisionProperties[redacted]"; }

    /** One configured decision model. The enclosing map key is its Spring and registry name. */
    @ApiStatus.Experimental
    public static final class Model {
        private String provider;
        private Typesafe typesafe;
        private Prompted prompted;
        private boolean readOnly;

        public String getProvider() { return provider; }
        public void setProvider(String provider) {
            requireMutable();
            this.provider = provider;
        }
        public Typesafe getTypesafe() { return typesafe; }
        public void setTypesafe(Typesafe typesafe) {
            requireMutable();
            this.typesafe = typesafe;
        }
        public Prompted getPrompted() { return prompted; }
        public void setPrompted(Prompted prompted) {
            requireMutable();
            this.prompted = prompted;
        }
        public Typesafe typesafe() { return getTypesafe(); }
        public Prompted prompted() { return getPrompted(); }

        void select(Typesafe typesafe) { setTypesafe(typesafe); }
        void select(Prompted prompted) { setPrompted(prompted); }

        private Model immutableCopy() {
            Model copy = new Model();
            copy.provider = provider;
            copy.typesafe = typesafe == null ? null : typesafe.immutableCopy();
            copy.prompted = prompted == null ? null : prompted.immutableCopy();
            copy.readOnly = true;
            return copy;
        }

        private void requireMutable() {
            if (readOnly) throw new UnsupportedOperationException("Decision model configuration is read-only");
        }

        @Override public String toString() { return "DecisionProperties.Model[redacted]"; }
    }

    /** TypeSafe System One settings for one named registry facade. */
    @ApiStatus.Experimental
    public static final class Typesafe {
        private String model;
        private URI baseUrl = URI.create("https://api.typesafe.ai");
        private Duration connectTimeout = Duration.ofSeconds(10);
        private boolean readOnly;

        public String getModel() { return model; }
        public void setModel(String model) {
            requireMutable();
            this.model = model;
        }
        public URI getBaseUrl() { return baseUrl; }
        public void setBaseUrl(URI baseUrl) {
            requireMutable();
            this.baseUrl = baseUrl;
        }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) {
            requireMutable();
            this.connectTimeout = connectTimeout;
        }

        private Typesafe immutableCopy() {
            Typesafe copy = new Typesafe();
            copy.model = model;
            copy.baseUrl = baseUrl;
            copy.connectTimeout = connectTimeout;
            copy.readOnly = true;
            return copy;
        }

        private void requireMutable() {
            if (readOnly) throw new UnsupportedOperationException("Decision model configuration is read-only");
        }
        @Override public String toString() { return "DecisionProperties.Typesafe[redacted]"; }
    }

    /** Prompted decision settings for one named registry facade. */
    @ApiStatus.Experimental
    public static final class Prompted {
        private String llmBeanName;
        private String optionsBeanName;
        private boolean readOnly;

        public String getLlmBeanName() { return llmBeanName; }
        public void setLlmBeanName(String llmBeanName) {
            requireMutable();
            this.llmBeanName = llmBeanName;
        }
        public String getOptionsBeanName() { return optionsBeanName; }
        public void setOptionsBeanName(String optionsBeanName) {
            requireMutable();
            this.optionsBeanName = optionsBeanName;
        }

        private Prompted immutableCopy() {
            Prompted copy = new Prompted();
            copy.llmBeanName = llmBeanName;
            copy.optionsBeanName = optionsBeanName;
            copy.readOnly = true;
            return copy;
        }

        private void requireMutable() {
            if (readOnly) throw new UnsupportedOperationException("Decision model configuration is read-only");
        }
        @Override public String toString() { return "DecisionProperties.Prompted[redacted]"; }
    }
}
