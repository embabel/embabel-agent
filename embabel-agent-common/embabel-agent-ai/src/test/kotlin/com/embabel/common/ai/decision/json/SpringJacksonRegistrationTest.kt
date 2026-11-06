/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.common.ai.decision.json

import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Questions
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.DatabindException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * Checks that the mappers a real application gets read and write the decision types with no
 * module or other setup: Spring Boot's auto-configured mapper and the platform's own mapper.
 */
class SpringJacksonRegistrationTest {

    private val urgent = Questions.named("is_urgent").proposition("Does this convey urgency?").build()
    private val spec = DecisionSpec.of(urgent)
    private val jev = ModelProvenance("jev-latest", "typesafe")

    private fun answered(): DecisionResponse = DecisionResponse.builder(spec)
        .answer(urgent, PropositionResult.Answered(true, jev, 0.93))
        .build()

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))

    @Test
    fun `Spring Boot's auto-configured mapper round-trips a spec and a response`() {
        contextRunner.run { context ->
            val mapper = context.getBean(JsonMapper::class.java)

            val specJson = mapper.writeValueAsString(spec)
            assertEquals(spec, mapper.readValue(specJson, DecisionSpec::class.java))

            val response = answered()
            val responseJson = mapper.writeValueAsString(response)
            assertEquals(response, mapper.readValue(responseJson, DecisionResponse::class.java))
        }
    }

    @Test
    fun `Spring Boot's mapper ignores unknown properties and still rejects an unknown member`() {
        contextRunner.run { context ->
            val mapper = context.getBean(JsonMapper::class.java)
            assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))

            val json = mapper.writeValueAsString(spec).replaceFirst("{", """{"owner":"support",""")
            val error = assertThrows(DatabindException::class.java) { mapper.readValue(json, DecisionSpec::class.java) }
            assertTrue(error.message!!.contains("Unknown member 'owner' in DecisionSpec")) { error.message!! }
            assertTrue(error.message!!.contains("(a string)")) { error.message!! }
            assertFalse(error.message!!.contains("support")) { error.message!! }
        }
    }

    @Test
    fun `the platform holder's default mapper round-trips a spec and a response`() {
        val mapper = EmbabelObjectMapperHolder.createDefault().get()

        val specJson = mapper.writeValueAsString(spec)
        assertEquals(spec, mapper.readValue(specJson, DecisionSpec::class.java))

        val response = answered()
        val responseJson = mapper.writeValueAsString(response)
        assertEquals(response, mapper.readValue(responseJson, DecisionResponse::class.java))
    }
}
