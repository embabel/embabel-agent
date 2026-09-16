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
import com.embabel.agent.cache.support.SpringCacheAgentCacheProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;

/**
 * Builds an {@link AgentCacheProvider} over the application's Spring
 * {@link CacheManager} when explicitly requested with
 * {@code embabel.agent.platform.cache.provider=spring-cache}.
 *
 * <p>Ordered after Spring Boot's cache auto-configuration so a {@code CacheManager}
 * it creates is visible. Referenced by name so this module does not depend on
 * {@code spring-boot-cache}.
 */
@AutoConfiguration(afterName = "org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration")
@ConditionalOnClass(SpringCacheAgentCacheProvider.class)
@ConditionalOnProperty(
        prefix = AgentCacheProperties.PREFIX,
        name = "provider",
        havingValue = AgentCacheProperties.SPRING_CACHE
)
public class AgentCacheProviderAutoConfiguration {

    /**
     * Fails rather than skipping when no {@code CacheManager} exists: the provider
     * was requested explicitly, and a request that silently does nothing would leave
     * agent processes non-durable with no indication why.
     */
    @Bean
    @ConditionalOnMissingBean(AgentCacheProvider.class)
    public AgentCacheProvider springCacheAgentCacheProvider(ObjectProvider<CacheManager> cacheManager) {
        CacheManager manager = cacheManager.getIfAvailable();
        if (manager == null) {
            throw new IllegalStateException(
                    AgentCacheProperties.PREFIX + ".provider=" + AgentCacheProperties.SPRING_CACHE
                            + " requires a CacheManager bean. Declare one, or enable Spring Boot's cache"
                            + " auto-configuration with @EnableCaching.");
        }
        return new SpringCacheAgentCacheProvider(manager);
    }
}
