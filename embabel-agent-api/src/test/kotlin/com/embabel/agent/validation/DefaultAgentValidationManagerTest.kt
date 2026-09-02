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

import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.spi.validation.DefaultAgentStructureValidator
import com.embabel.agent.spi.validation.DefaultAgentValidationManager
import com.embabel.agent.spi.validation.GoapPathToCompletionValidator
import com.embabel.common.core.validation.ValidationErrorCodes
import org.junit.jupiter.api.Test
import org.springframework.context.support.GenericApplicationContext
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultAgentValidationManagerTest {

    @Test
    fun `goal without matching action is invalid`() {
        val ac = GenericApplicationContext()
        ac.refresh()
        val manager = DefaultAgentValidationManager(
            validators = listOf(
                DefaultAgentStructureValidator(ac),
                GoapPathToCompletionValidator(),
            )
        )
        val r = manager.validate(evenMoreEvilWizard())
        assertFalse(r.isValid)
        assertTrue(
            r.errors.any { it.code == ValidationErrorCodes.GOAL_ACTION_NOT_FOUND }
        )
    }

}
