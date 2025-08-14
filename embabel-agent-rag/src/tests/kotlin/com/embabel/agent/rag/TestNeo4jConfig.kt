package com.embabel.agent.rag

import com.embabel.agent.rag.neo.common.LogicalQueryResolver
import com.embabel.agent.rag.neo.ogm.OgmCypherSearch
import org.mockito.Mockito
import org.neo4j.ogm.session.Session
import org.neo4j.ogm.session.SessionFactory
import org.neo4j.ogm.session.event.Event
import org.neo4j.ogm.session.event.EventListenerAdapter
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.neo4j.transaction.Neo4jTransactionManager

@Configuration
@AutoConfigurationPackage
@EntityScan("com.embabel.agent.rag.schema")
class TestNeo4jConfig {
    @Bean
    fun neo4jTestContainer(): Neo4jTestContainer {
        return Neo4jTestContainer.instance
    }

    @Bean
    fun configuration(): org.neo4j.ogm.config.Configuration? {
        val c = neo4jTestContainer()
        return org.neo4j.ogm.config.Configuration.Builder()
            .uri(c.boltUrl)
            .credentials("neo4j", c.adminPassword)
            .build()
    }

    @Bean
    fun sessionFactory(): SessionFactory {
        return object : SessionFactory(configuration(), "com.embabel.agent.rag") {
            override fun openSession(): Session {
                val s = super.openSession()
                s.register(preSaveEventListener())
                return s
            }
        }
    }

    @Bean
    fun transactionManager(): Neo4jTransactionManager {
        return Neo4jTransactionManager(sessionFactory())
    }

    @Bean
    fun preSaveEventListener(): EventListenerAdapter {
        return object : EventListenerAdapter() {
            override fun onPreSave(event: Event?) {
                // Add pre-save hook here, if needed
            }
        }
    }

    // --- Only the dependencies OgmCypherSearch needs ---
    @Bean
    fun logicalQueryResolver(): LogicalQueryResolver? {
        // Minimal stub; replace with a real test impl if you prefer
        return Mockito.mock<LogicalQueryResolver?>(LogicalQueryResolver::class.java)
    }

    @Bean
    fun ogmCypherSearch(
        sessionFactory: SessionFactory,
        logicalQueryResolver: LogicalQueryResolver,
    ): OgmCypherSearch {
        return OgmCypherSearch(sessionFactory, logicalQueryResolver)
    }
}