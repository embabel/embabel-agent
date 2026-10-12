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
package com.embabel.agent.autoconfigure.models.clef

import com.embabel.agent.clef.*
import com.embabel.agent.config.models.clef.ClefProperties
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.net.URI

class AgentClefAutoConfigurationTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentClefAutoConfiguration::class.java))
    private val prefix = "embabel.agent.platform.models.clef."
    @Test
    fun `disabled and missing module have no factory despite unusable endpoint settings`() {
        runner.withPropertyValues(prefix+"base-url=PRIVATE").run { assertThat(it).hasNotFailed().doesNotHaveBean(ClefModelFactory::class.java) }
        runner.withClassLoader(FilteredClassLoader(ClefModelFactory::class.java)).withPropertyValues(prefix+"enabled=true").run {
            assertThat(it).hasNotFailed().doesNotHaveBean(ClefModelFactory::class.java)
        }
    }
    @Test
    fun `enabled requires explicit valid bounded loopback settings and rejects unknown fields`() {
        listOf(arrayOf(prefix+"enabled=true"), arrayOf(prefix+"enabled=true", prefix+"base-url=http://example.com"),
            arrayOf(prefix+"enabled=true", prefix+"base-url=http://localhost", prefix+"max-request-bytes=16385"),
            arrayOf(prefix+"enabled=true", prefix+"base-url=http://localhost", prefix+"unknown=bad")).forEach { values ->
            runner.withPropertyValues(*values).run { assertThat(it).hasFailed() }
        }
        runner.withPropertyValues(prefix+"enabled=true", prefix+"base-url=http://localhost:8765").run {
            assertThat(it).hasNotFailed().hasSingleBean(ClefModelFactory::class.java)
            assertThat(it.getBean(ClefModelFactory::class.java).id).isEqualTo("clef")
        }
    }
    @Test
    fun `application factory replaces default with zero calls or bound invalid properties`() {
        val replacement = mockk<ClefModelFactory>()
        every { replacement.close() } just Runs
        runner.withBean("customClef", ClefModelFactory::class.java, { replacement })
            .withPropertyValues(prefix+"enabled=true", prefix+"base-url=PRIVATE").run {
                assertThat(it).hasNotFailed(); assertThat(it.getBean(ClefModelFactory::class.java)).isSameAs(replacement)
                verify { replacement wasNot Called }
            }
        verify(exactly = 1) { replacement.close() }
    }
}
