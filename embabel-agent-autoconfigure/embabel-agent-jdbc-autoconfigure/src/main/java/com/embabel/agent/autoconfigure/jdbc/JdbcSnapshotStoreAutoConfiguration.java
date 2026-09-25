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
package com.embabel.agent.autoconfigure.jdbc;

import com.embabel.agent.jdbc.JdbcAgentProcessSnapshotStore;
import com.embabel.agent.spi.config.spring.AgentProcessPersistenceProperties;
import com.embabel.agent.spi.persistence.AgentProcessSnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registers a {@link JdbcAgentProcessSnapshotStore} when
 * {@code embabel.agent.platform.persistence.provider=jdbc} is set and a
 * {@link JdbcTemplate} bean is present.
 *
 * <p>Ordered after Spring Boot's JDBC template auto-configuration so the
 * {@link JdbcTemplate} it creates is visible here.
 *
 * <p>Skipped when the application declares its own
 * {@link AgentProcessSnapshotStore} bean.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration")
@ConditionalOnClass({JdbcAgentProcessSnapshotStore.class, JdbcTemplate.class})
@ConditionalOnProperty(
        prefix = "embabel.agent.platform.persistence", // mirrors AgentProcessPersistenceProperties.PREFIX
        name = "provider",
        havingValue = "jdbc"
)
public class JdbcSnapshotStoreAutoConfiguration {

    public static final String PROVIDER_VALUE = "jdbc";

    /**
     * Fails rather than skipping when no {@link JdbcTemplate} exists: the provider
     * was requested explicitly, so a silent no-op would leave processes non-durable
     * with no indication why.
     */
    @Bean
    @ConditionalOnMissingBean(AgentProcessSnapshotStore.class)
    public AgentProcessSnapshotStore jdbcAgentProcessSnapshotStore(
            ObjectProvider<JdbcTemplate> jdbcTemplate) {
        JdbcTemplate template = jdbcTemplate.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException(
                    "%s.provider=%s requires a JdbcTemplate bean. Declare a DataSource or add spring-boot-starter-jdbc."
                            .formatted(AgentProcessPersistenceProperties.PREFIX, PROVIDER_VALUE));
        }
        return new JdbcAgentProcessSnapshotStore(template);
    }
}
