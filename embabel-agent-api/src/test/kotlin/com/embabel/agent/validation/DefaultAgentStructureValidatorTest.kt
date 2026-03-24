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
package com.embabel.agent.validation

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.annotation.support.AgentWithDuplicateActionNames
import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.spi.validation.DefaultAgentStructureValidator
import com.embabel.common.core.validation.ValidationErrorCodes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext

class DefaultAgentStructureValidatorTest {

    private fun validator(): DefaultAgentStructureValidator {
        val applicationContext = GenericApplicationContext()
        applicationContext.refresh()
        return DefaultAgentStructureValidator(applicationContext)
    }

    @Nested
    inner class Valid {

        @Test
        fun `no agents`() {
            validator().afterPropertiesSet()
        }

        @Test
        fun `evil wizard`() {
            val result = validator().validate(evenMoreEvilWizard())
            assertEquals(0, result.errors.size, "Expected no validation errors for evenMoreEvilWizard")
        }
    }

    @Nested
    inner class DuplicateActionNames {

        @Test
        fun `duplicate action names from overloaded methods should produce a validation error`() {
            val agentScope = AgentMetadataReader().createAgentMetadata(AgentWithDuplicateActionNames())
                ?: error("Expected metadata for AgentWithDuplicateActionNames")

            val result = validator().validate(agentScope)

            assertTrue(
                result.errors.any { it.code == ValidationErrorCodes.DUPLICATE_ACTION_NAME },
                "Expected a DUPLICATE_ACTION_NAME validation error, but got: ${result.errors}",
            )
        }
    }
}
