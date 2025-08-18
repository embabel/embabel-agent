package com.embabel.test

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Session
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

@NeoIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jTestContainerTest(
    @Autowired private val testContainer: Neo4jTestContainer,
) {

    val boltUrl = testContainer.boltUrl
    val driver: Driver = GraphDatabase.driver(
        boltUrl,
        AuthTokens.basic("neo4j", testContainer.adminPassword)
    )

    @BeforeEach
    fun setup() {
        driver.session().executeWrite { tx ->
            tx.run("""
            CALL apoc.cypher.runFile("reference-data.cypher") YIELD fileName
            RETURN fileName
        """.trimIndent()).consume()
        }
    }

    @Test
    fun `should make APOC extended available`() {
        driver.session().use { session: Session ->
            val result = session.run(
                """
                RETURN apoc.date.format(timestamp(),'ms','yyyy') AS y
            """.trimIndent()
            )
            assertTrue(result.hasNext())
        }
    }

    @Test
    fun `should load reference data`() {
        driver.session().use { session ->
            val result = session.run(
                """                  
            MATCH (p:Person)
            RETURN p.name AS name
            ORDER BY name
            """.trimIndent()
            )
            val names = result.list { it["name"].asString() }

            assertEquals(listOf("Arjen", "Igor", "Jasper", "Rod", "Sasha"), names)
        }
    }
}