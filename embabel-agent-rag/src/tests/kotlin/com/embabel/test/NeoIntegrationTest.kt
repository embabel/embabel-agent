package com.embabel.test

import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.data.neo4j.AutoConfigureDataNeo4j
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Stereotype annotation for Spring integration test using Neo
 */
@SpringBootTest(classes = [TestApplication::class])
@AutoConfigureDataNeo4j
@EnableAutoConfiguration
@Testcontainers
@ActiveProfiles("test")
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Transactional
annotation class NeoIntegrationTest