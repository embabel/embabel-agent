package com.embabel.common.ai.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test

class LlmOptionsDeserializationTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `serialize and deserialize`() {
        val options = LlmOptions(
            model = "gpt-3.5-turbo"
        )

        val ser = objectMapper.writeValueAsString(options)
        val deser = objectMapper.readValue(ser, LlmOptions::class.java)
    }
}