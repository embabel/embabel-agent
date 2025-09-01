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
package com.embabel.test

import com.embabel.agent.rag.neo.ogm.OgmRagService
import com.fasterxml.jackson.databind.ObjectMapper
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ResourceLoader

/**
 * Convenient superclass for Neo transactional integration tests using Testcontainers.
 * Test effects are rolled back by Spring test infrastructure.
 */
@NeoIntegrationTest
@Profile("test")
open class NeoIntegrationTestSupport {
    @Autowired
    protected var objectMapper: ObjectMapper? = null

    @Autowired
    protected var resourceLoader: ResourceLoader? = null

    @Autowired
    protected var applicationContext: ApplicationContext? = null

    @Autowired
    protected var ragService: OgmRagService? = null

    @Autowired
    var testContainer: Neo4jTestContainer? = null


    fun driver(): Driver = GraphDatabase.driver(
        testContainer!!.boltUrl,
        AuthTokens.basic("neo4j", testContainer!!.adminPassword)
    )
}
