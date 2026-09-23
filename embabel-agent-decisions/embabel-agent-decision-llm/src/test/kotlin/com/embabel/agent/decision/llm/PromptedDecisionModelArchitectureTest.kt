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
package com.embabel.agent.decision.llm

import com.embabel.agent.decision.DecisionModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class PromptedDecisionModelArchitectureTest {
    @Test
    fun `factory exposes only DecisionModel construction`() {
        val publicMethods = PromptedDecisionModel::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) }

        assertThat(PromptedDecisionModel::class.java.declaredConstructors).allMatch { Modifier.isPrivate(it.modifiers) }
        assertThat(publicMethods).allMatch { it.name == "create" && it.returnType == DecisionModel::class.java }
        assertThat(publicMethods).hasSize(2)
    }
}
