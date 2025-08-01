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
package com.embabel.agent.rag.neo.config

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.util.loggerFor
import org.neo4j.driver.Driver
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.neo4j.Neo4jVectorStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("neo")
class NeoVectorStoreConfiguration {

    init {
        loggerFor<NeoVectorStoreConfiguration>().info("Initializing Neo VectorStore")
    }

    @Bean
    fun neoVectorStore(driver: Driver, embeddingService: EmbeddingService): VectorStore {
        return Neo4jVectorStore.builder(
            driver,
            embeddingService.model,
        ).build()
    }
}
