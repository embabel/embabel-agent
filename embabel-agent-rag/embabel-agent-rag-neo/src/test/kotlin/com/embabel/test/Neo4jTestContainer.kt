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

import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Paths

class Neo4jTestContainer : Neo4jContainer<Neo4jTestContainer> {
    private constructor(imageName: String) : super(imageName)

    companion object {
        @JvmField
        val instance: Neo4jTestContainer = Neo4jTestContainer("neo4j:5.26.1-enterprise")
            .apply {
                // Resolve the JAR in the user's ~/.m2 repository
                // Mount it inside the container's plugins directory
                withFileSystemBind(apocJarPath(), "/plugins/apoc-extended-5.26.0.jar")
            }
            .withNeo4jConfig("dbms.security.procedures.unrestricted", "apoc.*")
            .withNeo4jConfig("dbms.logs.query.enabled", "INFO")
            .withNeo4jConfig("dbms.logs.query.parameter_logging_enabled", "true")
            .withNeo4jConfig("apoc.import.file.enabled", "true")
            .withEnv("NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes")
            .withEnv("NEO4J_PLUGINS", "[\"apoc\",\"graph-data-science\"]")
            .withEnv(
                "APOC_CONFIG",
                "apoc.import.file.enabled=true,apoc.import.file.use_neo4j_config=true"
            )
            .withEnv("checks.disable", "true")
            .withEnv("NEO4J_apoc_export_file_enabled", "true")
            .withFileSystemBind("../../../test-neo4j-logs", "/logs")
            .withAdminPassword("embabel$$$$")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("reference-data.cypher"),
                "/var/lib/neo4j/import/reference-data.cypher"
            )

        //TODO: We might be able to clean this up using copyFileToContainer and a classpath resource
        private fun apocJarPath(): String {
            val apocJar = Paths.get(
                System.getProperty("user.home"),
                ".m2",
                "repository",
                "org",
                "neo4j",
                "procedure",
                "apoc-extended",
                "5.26.0",
                "apoc-extended-5.26.0.jar"
            ).toString()
            return apocJar
        }


        init {
            instance.start()
        }
    }
}
