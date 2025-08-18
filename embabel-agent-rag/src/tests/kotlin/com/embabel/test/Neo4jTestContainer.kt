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
