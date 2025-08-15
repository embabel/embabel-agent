package com.embabel.test

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ResourceLoader

/**
 * Convenient superclass for Neo transactional integration tests using Testcontainers.
 * Test effects are rolled back by Spring test infrastructure.
 */
@NeoIntegrationTest
@Profile("test")
class NeoIntegrationTestSupport {
    @Autowired
    protected var objectMapper: ObjectMapper? = null

    @Autowired
    protected var resourceLoader: ResourceLoader? = null

    @Autowired
    protected var applicationContext: ApplicationContext? = null
}
