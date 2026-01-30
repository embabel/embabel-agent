package com.embabel.common.ai.model

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class LlmOptionsPropertiesTest {

    @Test
    fun testDefaultValues() {
        val options = LlmOptionsProperties()
        assertNotNull(options)
    }

    @Test
    fun testHttpHeaders() {
        val options = LlmOptionsProperties()
        options.httpHeaders = mapOf("Authorization" to "Bearer test-token")
        assertNotNull(options.httpHeaders)
        assert(options.httpHeaders["Authorization"] == "Bearer test-token")
    }
}
