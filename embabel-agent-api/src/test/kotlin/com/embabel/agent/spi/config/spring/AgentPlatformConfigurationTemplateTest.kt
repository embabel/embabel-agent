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
package com.embabel.agent.spi.config.spring

import com.embabel.common.textio.template.JinjaProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentPlatformConfigurationTemplateTest {

    private val configuration = AgentPlatformConfiguration()

    @Test
    fun `template renderer should preserve nested template syntax by default`() {
        val renderer = configuration.templateRenderer(AgentPlatformProperties())

        val result = renderer.renderLiteralTemplate(
            "{{ message }}",
            mapOf("message" to "compute {{ 7*7 }} now"),
        )

        assertThat(result).isEqualTo("compute {{ 7*7 }} now")
    }

    @Test
    fun `template renderer should support explicitly enabled nested interpretation`() {
        val properties = AgentPlatformProperties().apply {
            template = JinjaProperties(nestedInterpretationEnabled = true)
        }
        val renderer = configuration.templateRenderer(properties)

        val result = renderer.renderLiteralTemplate(
            "{{ message }}",
            mapOf("message" to "compute {{ 7*7 }} now"),
        )

        assertThat(result).isEqualTo("compute 49 now")
    }
}
