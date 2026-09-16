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

import com.embabel.agent.cache.AgentCacheIndex;
import com.embabel.agent.cache.AgentCacheProvider;
import com.embabel.agent.cache.AgentCacheRegion;
import com.embabel.agent.cache.CacheCapability;
import com.embabel.agent.cache.CacheRegionConfig;
import com.embabel.agent.cache.CacheRegions;
import com.embabel.agent.cache.support.SpringCacheAgentCacheProvider;
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wiring of cache-backed agent process persistence.
 *
 * <p>A mocked {@link AgentCacheProvider} isolates the decision under test, which is
 * whether and how the snapshot store is built, from any real backend. Tests that
 * exercise the Spring {@code CacheManager} adapter use a real one.
 */
class CacheSnapshotStoreAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentCacheProviderAutoConfiguration.class,
                    CacheSnapshotStoreAutoConfiguration.class
            ));

    private static AgentCacheProvider indexingProvider() {
        AgentCacheRegion region = mock(AgentCacheRegion.class);
        when(region.getName()).thenReturn(CacheRegions.AGENT_PROCESS_SNAPSHOTS);
        when(region.getCapabilities()).thenReturn(
                EnumSet.of(CacheCapability.SECONDARY_INDEX, CacheCapability.ATOMIC_COMPARE_AND_SET));
        when(region.index(anyString())).thenReturn(mock(AgentCacheIndex.class));
        AgentCacheProvider provider = mock(AgentCacheProvider.class);
        when(provider.getName()).thenReturn("test");
        when(provider.getRegion(any(CacheRegionConfig.class))).thenReturn(region);
        return provider;
    }

    @Test
    void doesNothingWithoutAProviderOrCacheManager() {
        runner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(AgentCacheProvider.class)
                .doesNotHaveBean(AgentProcessSnapshotStore.class));
    }

    @Nested
    class WithAnApplicationProvider {

        @Test
        void registersTheSnapshotStoreOverTheSnapshotRegion() {
            AgentCacheProvider provider = indexingProvider();
            runner.withBean(AgentCacheProvider.class, () -> provider)
                    .run(context -> {
                        assertThat(context).hasSingleBean(AgentProcessSnapshotStore.class);
                        ArgumentCaptor<CacheRegionConfig> config = ArgumentCaptor.forClass(CacheRegionConfig.class);
                        verify(provider).getRegion(config.capture());
                        assertThat(config.getValue().getName()).isEqualTo(CacheRegions.AGENT_PROCESS_SNAPSHOTS);
                    });
        }

        @Test
        void requiresCompareAndSetByDefault() {
            AgentCacheProvider provider = indexingProvider();
            runner.withBean(AgentCacheProvider.class, () -> provider)
                    .run(context -> {
                        ArgumentCaptor<CacheRegionConfig> config = ArgumentCaptor.forClass(CacheRegionConfig.class);
                        verify(provider).getRegion(config.capture());
                        assertThat(config.getValue().getRequiredCapabilities()).containsExactlyInAnyOrder(
                                CacheCapability.SECONDARY_INDEX, CacheCapability.ATOMIC_COMPARE_AND_SET);
                    });
        }

        @Test
        void requiresOnlyIndexingWhenCompareAndSetIsWaived() {
            AgentCacheProvider provider = indexingProvider();
            runner.withBean(AgentCacheProvider.class, () -> provider)
                    .withPropertyValues("embabel.agent.platform.cache.require-atomic-compare-and-set=false")
                    .run(context -> {
                        ArgumentCaptor<CacheRegionConfig> config = ArgumentCaptor.forClass(CacheRegionConfig.class);
                        verify(provider).getRegion(config.capture());
                        assertThat(config.getValue().getRequiredCapabilities())
                                .containsExactly(CacheCapability.SECONDARY_INDEX);
                    });
        }

        @Test
        void leavesAnApplicationSnapshotStoreInPlace() {
            AgentCacheProvider provider = indexingProvider();
            AgentProcessSnapshotStore applicationStore = mock(AgentProcessSnapshotStore.class);
            runner.withBean(AgentCacheProvider.class, () -> provider)
                    .withBean(AgentProcessSnapshotStore.class, () -> applicationStore)
                    .run(context -> {
                        assertThat(context.getBean(AgentProcessSnapshotStore.class)).isSameAs(applicationStore);
                        verify(provider, never()).getRegion(any(CacheRegionConfig.class));
                    });
        }

        @Test
        void doesNotRegisterAStoreWhenPersistenceIsDisabled() {
            // Disabled persistence must not touch the provider either: a region the
            // backend cannot supply would otherwise fail startup for nothing.
            AgentCacheProvider provider = indexingProvider();
            runner.withBean(AgentCacheProvider.class, () -> provider)
                    .withPropertyValues("embabel.agent.platform.persistence.enabled=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed().doesNotHaveBean(AgentProcessSnapshotStore.class);
                        verify(provider, never()).getRegion(any(CacheRegionConfig.class));
                    });
        }
    }

    @Nested
    class WithASpringCacheManager {

        private final ApplicationContextRunner withCacheManager =
                runner.withBean(CacheManager.class, ConcurrentMapCacheManager::new);

        @Test
        void aCacheManagerAloneDoesNotActivatePersistence() {
            withCacheManager.run(context -> assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(AgentCacheProvider.class)
                    .doesNotHaveBean(AgentProcessSnapshotStore.class));
        }

        @Test
        void activatesWhenRequestedAndCompareAndSetIsWaived() {
            withCacheManager
                    .withPropertyValues(
                            "embabel.agent.platform.cache.provider=spring-cache",
                            "embabel.agent.platform.cache.require-atomic-compare-and-set=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed()
                                .hasSingleBean(AgentProcessSnapshotStore.class);
                        assertThat(context.getBean(AgentCacheProvider.class))
                                .isInstanceOf(SpringCacheAgentCacheProvider.class);
                    });
        }

        @Test
        void failsStartupWithDefaultsAndNamesTheProperty() {
            // The Spring adapter cannot guarantee compare-and-set; failing here is the
            // point, and the message must tell the user how to proceed.
            withCacheManager
                    .withPropertyValues("embabel.agent.platform.cache.provider=spring-cache")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .hasMessageContaining(CacheCapability.ATOMIC_COMPARE_AND_SET.name());
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("require-atomic-compare-and-set=false");
                    });
        }

        @Test
        void failsStartupWhenRequestedWithoutACacheManager() {
            runner.withPropertyValues(
                            "embabel.agent.platform.cache.provider=spring-cache",
                            "embabel.agent.platform.cache.require-atomic-compare-and-set=false")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("requires a CacheManager bean");
                    });
        }

        @Test
        void anApplicationProviderTakesPrecedenceOverTheSpringCacheAdapter() {
            AgentCacheProvider provider = indexingProvider();
            withCacheManager
                    .withBean(AgentCacheProvider.class, () -> provider)
                    .withPropertyValues("embabel.agent.platform.cache.provider=spring-cache")
                    .run(context -> assertThat(context.getBean(AgentCacheProvider.class)).isSameAs(provider));
        }
    }
}
