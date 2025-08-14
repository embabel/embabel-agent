package com.embabel.agent.rag

import org.testcontainers.containers.Neo4jContainer

class Neo4jTestContainer : Neo4jContainer<Neo4jTestContainer> {
    private constructor(imageName: String) : super(imageName)

    companion object {
        @JvmField
        val instance: Neo4jTestContainer = Neo4jTestContainer(imageName = "neo4j:5.26.1-enterprise")
            .withNeo4jConfig("dbms.security.procedures.unrestricted", "apoc.*")
            .withNeo4jConfig("dbms.logs.query.enabled", "INFO")
            .withNeo4jConfig("dbms.logs.query.parameter_logging_enabled", "true")
            .withEnv("NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes")
            .withEnv("NEO4J_PLUGINS", "[\"apoc\"]")
            .withEnv("checks.disable", "true")
            .withFileSystemBind(
                "../../../test-neo4j-logs", "/logs"
            ) // add absolute path for logging queries
            .withAdminPassword("embabel$$$$")

        init {
            instance.start()
        }
    }
}