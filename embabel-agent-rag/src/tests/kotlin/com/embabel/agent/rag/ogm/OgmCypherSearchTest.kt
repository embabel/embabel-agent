package com.embabel.agent.rag.ogm

import com.embabel.agent.rag.NeoIntegrationTestSupport
import com.embabel.agent.rag.neo.ogm.OgmCypherSearch
import com.embabel.agent.spi.Ranking
import com.embabel.agent.spi.Rankings
import com.embabel.agent.testing.integration.FakeRanker
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles

@TestConfiguration
class FakeConfig {

    @Bean
    @Primary
    fun fakeRanker() = object : FakeRanker {

        override fun <T> rank(
            description: String,
            userInput: String,
            rankables: Collection<T>,
        ): Rankings<T> where T : Named, T : Described {
            when (description) {
                "agent" -> {
                    val a = rankables.firstOrNull { it.name.contains("Star") } ?: fail { "No agent with Star found" }
                    return Rankings(
                        rankings = listOf(Ranking(a, .9))
                    )
                }

                "goal" -> {
                    val g =
                        rankables.firstOrNull { it.description.contains("horoscope") } ?: fail("No goal with horoscope")
                    return Rankings(
                        rankings = listOf(Ranking(g, .9))
                    )
                }

                else -> throw IllegalArgumentException("Unknown description $description")
            }
        }
    }

}


@ActiveProfiles("test")
@Import(
    value = [
        FakeConfig::class,
    ]
)
class OgmCypherSearchTest(@Autowired private val ogmCypherSearch: OgmCypherSearch)
    : NeoIntegrationTestSupport() {

    @Test
    fun should_query() {
        val query = "match (n) return n limit 100"
        val params : Map<String, *> = emptyMap<String, Any>()
        val result = ogmCypherSearch.query("purpose", query, params, null)
        println("Got result: $result")
        assertNotNull(result)
    }
}