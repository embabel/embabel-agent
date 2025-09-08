/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.rag.neo.ogm

import org.neo4j.ogm.session.SessionFactory
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories
import org.springframework.data.neo4j.transaction.Neo4jTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement


@Configuration
@EnableNeo4jRepositories
@EnableTransactionManagement
@Profile("!test")
class NeoOgmConfig(
    private val properties: NeoRagServiceProperties,
) {

    private val logger = LoggerFactory.getLogger(NeoOgmConfig::class.java)

    @Bean
    fun ogmConfiguration(): org.neo4j.ogm.config.Configuration {
        logger.info("Connecting to Neo4j at {} as user {}", properties.uri, properties.username)
        return org.neo4j.ogm.config.Configuration.Builder()
            .uri(properties.uri)
            .credentials(properties.username, properties.password)
            .build()
    }

    @Bean
    fun sessionFactory(): SessionFactory {
        return SessionFactory(
            ogmConfiguration(),
            *properties.packages.toTypedArray(),
        )
    }

    @Bean
    @Primary
    fun transactionManager(): PlatformTransactionManager {
        return Neo4jTransactionManager(sessionFactory())
    }

}
