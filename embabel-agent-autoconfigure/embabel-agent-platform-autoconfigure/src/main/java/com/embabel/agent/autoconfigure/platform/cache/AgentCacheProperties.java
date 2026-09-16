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
package com.embabel.agent.autoconfigure.platform.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for cache-backed agent process persistence.
 *
 * <p>Cache-backed persistence activates when an
 * {@code com.embabel.agent.cache.AgentCacheProvider} bean is present. Declaring
 * one is an explicit statement of intent. A Spring {@code CacheManager} alone is
 * not: applications commonly have one for unrelated caching, and silently
 * treating it as durable agent state would be a trap. Set {@link #provider} to
 * {@value #SPRING_CACHE} to opt in.
 */
@ConfigurationProperties(prefix = AgentCacheProperties.PREFIX)
public class AgentCacheProperties {

    public static final String PREFIX = "embabel.agent.platform.cache";

    /**
     * Value of {@link #provider} that builds a provider over the application's
     * Spring {@code CacheManager}.
     */
    public static final String SPRING_CACHE = "spring-cache";

    /**
     * Cache provider to build automatically. Currently only {@value #SPRING_CACHE},
     * which adapts the application's Spring {@code CacheManager}. Leave unset when
     * declaring an {@code AgentCacheProvider} bean directly.
     */
    private String provider;

    /**
     * Whether the process snapshot region must guarantee atomic compare-and-set.
     *
     * <p>Without it, two nodes checkpointing the same process concurrently can lose
     * an update. The Spring {@code CacheManager} adapter cannot provide it, so using
     * that adapter requires setting this to {@code false}, which is safe only when a
     * single node writes each process.
     */
    private boolean requireAtomicCompareAndSet = true;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public boolean isRequireAtomicCompareAndSet() {
        return requireAtomicCompareAndSet;
    }

    public void setRequireAtomicCompareAndSet(boolean requireAtomicCompareAndSet) {
        this.requireAtomicCompareAndSet = requireAtomicCompareAndSet;
    }
}
