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
package com.embabel.agent.config.models.byok

import com.embabel.common.ai.model.ByRoleModelSelectionCriteria
import com.embabel.common.ai.model.ConfigurableModelProvider
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.DefaultModelSelectionCriteria
import com.embabel.common.ai.model.NoSuitableModelException
import com.embabel.common.ai.model.ModelProvider.Companion.BEST_ROLE
import com.embabel.common.ai.model.ModelProvider.Companion.CHEAPEST_ROLE
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A pure BYOK deployment holds no provider key, so no model a role names is registered — and
 * roles are ordinary configuration that such a deployment may well already have.
 *
 * Treating that as fatal made the starter unable to do the one thing it exists for: an
 * application whose `application.yml` named any role at all failed context refresh with
 * "LLM 'x' for role y is not available", no matter that `default-llm` pointed at the
 * placeholder.
 */
class PureByokWithRolesTest {

    private fun pureByok(roles: Map<String, String>) = ConfigurableModelProvider(
        llms = listOf(SetupRequiredLlm.llmService()),
        embeddingServices = emptyList(),
        properties = ConfigurableModelProviderProperties(
            llms = roles,
            defaultLlm = SetupRequiredLlm.NAME,
        ),
    )

    @Test
    fun `starts with roles configured and no model to satisfy them`() {
        assertThatCode {
            pureByok(mapOf(BEST_ROLE to "gpt-4.1", CHEAPEST_ROLE to "gpt-4.1-nano"))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `still resolves the placeholder as the default`() {
        val modelProvider = pureByok(mapOf(BEST_ROLE to "gpt-4.1"))

        assertThat(modelProvider.getLlm(DefaultModelSelectionCriteria).name)
            .isEqualTo(SetupRequiredLlm.NAME)
    }

    @Test
    fun `an unsatisfiable role fails when asked for, rather than resolving to the placeholder`() {
        // Silently handing back the placeholder would turn "no key configured" into an empty or
        // broken answer at some unrelated point later. The role has no model; say so.
        val modelProvider = pureByok(mapOf(BEST_ROLE to "gpt-4.1"))

        assertThatThrownBy { modelProvider.getLlm(ByRoleModelSelectionCriteria(BEST_ROLE)) }
            .isInstanceOf(NoSuitableModelException::class.java)
    }
}
