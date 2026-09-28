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
package com.embabel.agent.config.models.dashscope

import com.embabel.agent.openai.OpenAiClientTimeouts
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.common.util.ObjectProviders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class DashScopeClientTimeoutsTest {

    @Test
    fun `configured timeouts reach the model factory`() {
        val properties = Binder(
            MapConfigurationPropertySource(
                mapOf(
                    "${DashScopeProperties.PREFIX}.connect-timeout" to "4s",
                    "${DashScopeProperties.PREFIX}.read-timeout" to "7m",
                ),
            ),
        ).bindOrCreate(DashScopeProperties.PREFIX, DashScopeProperties::class.java)

        val config = DashScopeModelsConfig(
            envBaseUrl = null,
            envApiKey = "test-key",
            observationRegistry = ObjectProviders.empty(),
            properties = properties,
            configurableBeanFactory = DefaultListableBeanFactory(),
            restClientBuilder = ObjectProviders.empty(),
            webClientBuilder = ObjectProviders.empty(),
        )

        assertEquals(
            OpenAiClientTimeouts(connect = Duration.ofSeconds(4), read = Duration.ofMinutes(7)),
            timeoutsOf(config),
        )
    }

    /** The factory keeps its timeouts protected; reading them here leaves its API as it is. */
    private fun timeoutsOf(factory: OpenAiCompatibleModelFactory): OpenAiClientTimeouts =
        OpenAiCompatibleModelFactory::class.java.getDeclaredMethod("getTimeouts")
            .apply { isAccessible = true }
            .invoke(factory) as OpenAiClientTimeouts
}
