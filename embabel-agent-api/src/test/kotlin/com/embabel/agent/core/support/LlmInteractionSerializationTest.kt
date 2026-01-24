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
package com.embabel.agent.core.support

import com.embabel.agent.api.common.InteractionId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test

/**
 * Test for GitHub issue #1309: Serialization issue with LlmInteraction.
 *
 * The problem is that LlmInteraction has:
 * 1. val id: InteractionId (where InteractionId is a value class)
 * 2. fun getId(): String (explicit method for Java compatibility)
 *
 * Jackson sees conflicting getter definitions for property "id":
 * - The mangled getter from the value class property
 * - The explicit getId() method
 */
class LlmInteractionSerializationTest {

    private val objectMapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `LlmInteraction can be serialized to JSON without conflicting getter error`() {
        val interaction = LlmInteraction(
            id = InteractionId("test-interaction"),
        )

        // This should not throw:
        // JsonMappingException: Conflicting getter definitions for property "id"
        val json = objectMapper.writeValueAsString(interaction)

        // Verify the id is present in the JSON
        assert(json.contains("test-interaction")) {
            "JSON should contain the interaction id value"
        }
    }
}
