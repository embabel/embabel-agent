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

import com.embabel.agent.cache.AgentCacheProvider;
import com.embabel.agent.cache.CacheCapability;
import com.embabel.agent.cache.CacheCapabilityException;
import com.embabel.agent.cache.CacheRegionConfig;
import com.embabel.agent.cache.CacheRegions;
import com.embabel.agent.cache.support.CacheBackedAgentProcessSnapshotStore;
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.EnumSet;
import java.util.Set;

/**
 * Registers a {@link CacheBackedAgentProcessSnapshotStore} when an
 * {@link AgentCacheProvider} is present, so declaring a cache backend is enough to
 * make agent processes durable.
 *
 * <p>The platform's {@code agentProcessRepository} bean discovers the resulting
 * {@link AgentProcessSnapshotStore} and decorates the runtime repository with it.
 * No ordering against the platform auto-configuration is needed: that bean
 * resolves the store lazily, after every bean definition is registered.
 *
 * <p>An application-defined {@link AgentProcessSnapshotStore} always takes
 * precedence.
 */
@AutoConfiguration(after = AgentCacheProviderAutoConfiguration.class)
@ConditionalOnClass(CacheBackedAgentProcessSnapshotStore.class)
@ConditionalOnProperty(
        prefix = "embabel.agent.platform.persistence",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(AgentCacheProperties.class)
public class CacheSnapshotStoreAutoConfiguration {

    @Bean
    @ConditionalOnBean(AgentCacheProvider.class)
    @ConditionalOnMissingBean(AgentProcessSnapshotStore.class)
    public AgentProcessSnapshotStore cacheBackedAgentProcessSnapshotStore(
            AgentCacheProvider agentCacheProvider,
            AgentCacheProperties properties
    ) {
        // The store resolves children through a secondary index, so that is always
        // required. Compare-and-set is required unless explicitly waived.
        Set<CacheCapability> required = properties.isRequireAtomicCompareAndSet()
                ? EnumSet.of(CacheCapability.SECONDARY_INDEX, CacheCapability.ATOMIC_COMPARE_AND_SET)
                : EnumSet.of(CacheCapability.SECONDARY_INDEX);
        try {
            return new CacheBackedAgentProcessSnapshotStore(
                    agentCacheProvider.getRegion(
                            new CacheRegionConfig(CacheRegions.AGENT_PROCESS_SNAPSHOTS, null, required)
                    )
            );
        } catch (CacheCapabilityException e) {
            throw new IllegalStateException(
                    "Cache provider [" + agentCacheProvider.getName() + "] cannot back durable agent"
                            + " process snapshots: " + e.getMessage() + " If only one node ever writes each"
                            + " process, set " + AgentCacheProperties.PREFIX
                            + ".require-atomic-compare-and-set=false.",
                    e);
        }
    }
}
