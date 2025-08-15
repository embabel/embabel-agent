package com.embabel.agent.rag.ogm

import com.embabel.test.NeoIntegrationTest
import com.embabel.agent.rag.neo.ogm.OgmCypherSearch
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired



@NeoIntegrationTest
class OgmCypherSearchTest(
    @Autowired private var ogmCypherSearch: OgmCypherSearch) {

    @Test
    fun should_query() {
        val query = "match (n) return n limit 100"
        val params : Map<String, *> = emptyMap<String, Any>()
        val result = ogmCypherSearch.query("purpose", query, params, null)
        println("Got result: $result")
        assertNotNull(result)
    }
}