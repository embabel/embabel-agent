package com.embabel.agent.rag.ogm

import com.embabel.agent.rag.TestNeo4jConfig
import com.embabel.agent.rag.neo.ogm.OgmCypherSearch
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.neo4j.DataNeo4jTest
import org.springframework.test.context.ContextConfiguration


@DataNeo4jTest
@ContextConfiguration(classes = [TestNeo4jConfig::class])
class OgmCypherSearchTest {

    @Autowired private lateinit var ogmCypherSearch: OgmCypherSearch

    @Test
    fun should_query() {
        val query = "match (n) return n limit 100"
        val params : Map<String, *> = emptyMap<String, Any>()
        val result = ogmCypherSearch.query("purpose", query, params, null)
        println("Got result: $result")
        assertNotNull(result)
    }
}