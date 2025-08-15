package com.embabel.test

import com.embabel.agent.config.AgentPlatformConfiguration
import com.embabel.agent.config.models.DockerLocalModels
import com.embabel.agent.config.models.OllamaModels
import org.neo4j.ogm.config.Configuration
import org.neo4j.ogm.session.Session
import org.neo4j.ogm.session.SessionFactory
import org.neo4j.ogm.session.event.Event
import org.neo4j.ogm.session.event.EventListenerAdapter
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.web.reactive.HttpHandlerAutoConfiguration
import org.springframework.boot.autoconfigure.web.reactive.WebFluxAutoConfiguration
import org.springframework.boot.autoconfigure.web.reactive.error.ErrorWebFluxAutoConfiguration
import org.springframework.boot.autoconfigure.websocket.reactive.WebSocketReactiveAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.data.neo4j.annotation.EnableNeo4jAuditing
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories
import org.springframework.data.neo4j.transaction.Neo4jTransactionManager
import org.springframework.test.context.ActiveProfiles


/**
 * Main entry point for integration tests
 */
@SpringBootApplication(
    exclude = [WebFluxAutoConfiguration::class, WebSocketReactiveAutoConfiguration::class, ErrorWebFluxAutoConfiguration::class, HttpHandlerAutoConfiguration::class
    ]
)
@EnableNeo4jRepositories(basePackages = ["com.embabel"])
@EnableNeo4jAuditing
@ConfigurationPropertiesScan(basePackages = ["com.embabel"])
@ComponentScan(basePackages = ["com.embabel"])
@ActiveProfiles("test")
@Import(AgentPlatformConfiguration::class, OllamaModels::class, DockerLocalModels::class)
open class TestApplication {

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(TestApplication::class.java, *args)
        }
    }

    @Bean
    fun neo4jTestContainer(): Neo4jTestContainer {
        return Neo4jTestContainer.instance
    }

    @Bean
    fun configuration(): Configuration? {
        val c = neo4jTestContainer()
        return Configuration.Builder()
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


}
