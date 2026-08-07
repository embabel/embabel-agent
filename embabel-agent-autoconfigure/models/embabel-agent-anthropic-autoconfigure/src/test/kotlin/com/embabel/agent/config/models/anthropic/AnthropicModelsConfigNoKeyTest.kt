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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.autoconfigure.models.anthropic.AgentAnthropicAutoConfiguration
import com.embabel.common.ai.autoconfig.ProviderInitialization
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment

/**
 * The starter is on the classpath whether or not a key is present — the appliance bakes
 * in both provider starters deliberately, so a runtime key works without a rebuild. An
 * absent key must therefore degrade to "no models registered", the way the LM Studio
 * config does when its server is unreachable, rather than kill the application context.
 *
 * Failing in the constructor was unconditional: a `@Configuration` whose superclass
 * constructor throws cannot be conditioned away, so the whole context went down with it.
 */
class AnthropicModelsConfigNoKeyTest {

    private val contextRunner = ApplicationContextRunner()
        // Drop the real environment. Without this the suite passes or fails according to
        // whether the machine running it exports ANTHROPIC_API_KEY — and a developer
        // machine always does, which is the exact reason this bug reached an appliance
        // undetected. `withPropertyValues` sets system properties, which outrank the
        // environment, so the keyed cases below still work with this removed.
        .withInitializer { context ->
            context.environment.propertySources.remove(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            )
        }
        .withConfiguration(AutoConfigurations.of(AgentAnthropicAutoConfiguration::class.java))

    @Test
    fun `starts and registers nothing when no api key is configured`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(ProviderInitialization::class.java)
            assertThat(context.getBean(ProviderInitialization::class.java).registeredLlms).isEmpty()
        }
    }

    @Test
    fun `treats a blank api key as absent`() {
        // Compose passes ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY:-}, so in a container the
        // variable is routinely set-but-empty. That resolves to "" rather than null, which
        // used to slip past the null check and hand a blank key to the client.
        contextRunner
            .withPropertyValues("ANTHROPIC_API_KEY=")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ProviderInitialization::class.java).registeredLlms).isEmpty()
            }
    }

    @Test
    fun `treats a whitespace-only api key as absent`() {
        contextRunner
            .withPropertyValues("ANTHROPIC_API_KEY=   ")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ProviderInitialization::class.java).registeredLlms).isEmpty()
            }
    }

    @Test
    fun `registers models when an api key is configured`() {
        // The other half of the gate: skipping registration when unkeyed must not stop
        // registration when keyed. No network call is made to build the model beans.
        contextRunner
            .withPropertyValues("ANTHROPIC_API_KEY=sk-ant-test-key")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ProviderInitialization::class.java).registeredLlms).isNotEmpty()
            }
    }

    @Test
    fun `property-configured key is honoured when the env var is absent`() {
        contextRunner
            .withPropertyValues("${AnthropicProperties.PREFIX}.api-key=sk-ant-test-key")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ProviderInitialization::class.java).registeredLlms).isNotEmpty()
            }
    }
}
