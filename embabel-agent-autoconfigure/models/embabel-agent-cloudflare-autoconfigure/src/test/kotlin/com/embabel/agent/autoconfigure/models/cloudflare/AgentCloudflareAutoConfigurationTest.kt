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
package com.embabel.agent.autoconfigure.models.cloudflare

import com.embabel.agent.config.models.cloudflare.CloudflareProperties
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.cloudflare.CloudflareClientOptions
import com.embabel.common.ai.decision.cloudflare.CloudflareModelFactory
import io.mockk.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.util.ReflectionTestUtils
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class AgentCloudflareAutoConfigurationTest {
    private val prefix = "embabel.agent.platform.models.cloudflare"
    private val account = "00000000000000000000000000000000"
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentCloudflareAutoConfiguration::class.java))
    private fun enabled(vararg settings: String) = runner.withPropertyValues("$prefix.enabled=true", "$prefix.account-id=$account", *settings)

    @Nested
    inner class Activation {
        @Test
        fun `absent or disabled integration leaves lazy model beans untouched`() {
            listOf(emptyArray<String>(), arrayOf("$prefix.enabled=false", "$prefix.account-id=bad", "$prefix.timeout=0s", "$prefix.unknown=ignored"))
                .forEach { settings -> runner.withPropertyValues(*settings).withInitializer { context ->
                    (context.beanFactory as BeanDefinitionRegistry).registerBeanDefinition("unused", BeanDefinitionBuilder
                        .genericBeanDefinition(CloudflareModelFactory::class.java) { error("must remain lazy") }.setLazyInit(true).beanDefinition)
                }.run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context.beanFactory.containsSingleton("unused")).isFalse()
                    assertThat(context).doesNotHaveBean(CloudflareProperties::class.java)
                    assertThat(context).doesNotHaveBean(DecisionService::class.java)
                } }
        }

        @Test
        fun `missing provider classes back off even when enabled`() {
            enabled().withClassLoader(FilteredClassLoader("com.embabel.common.ai.decision.cloudflare")).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(CloudflareProperties::class.java)
            }
        }

        @Test
        fun `enabled factory has a local default catalog without credentials or services`() {
            enabled().run { context ->
                assertThat(context).hasNotFailed()
                val factory = context.getBean(CloudflareModelFactory::class.java)
                assertThat(factory.models().map { it.modelName }).containsExactlyElementsOf(CloudflareModelFactory.SUPPORTED_MODELS)
                assertThat(factory.create(CloudflareModelFactory.SUPPORTED_MODELS.first()).name).isEqualTo("@cf/cloudflare/clef")
                assertThat(context).doesNotHaveBean(DecisionService::class.java)
                assertThat(context.getBean(CloudflareProperties::class.java).toString()).doesNotContain(account)
            }
        }

        @Test
        fun `declared application factory bypasses properties and closes without resolving its token`() {
            val tokens = AtomicInteger()
            val factory = spyk(CloudflareModelFactory(CloudflareClientOptions(account), tokenSupplier = Supplier { tokens.incrementAndGet(); error("token resolved") }))
            enabled("$prefix.account-id=bad", "$prefix.unknown=ignored").withBean(CloudflareModelFactory::class.java, { factory }).run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(CloudflareModelFactory::class.java)).isSameAs(factory)
                assertThat(context).doesNotHaveBean(CloudflareProperties::class.java)
                assertThat(tokens.get()).isZero()
            }
            verify(exactly = 1) { factory.close() }
        }

        @Test
        fun `automatically configured factory closes with the context`() {
            lateinit var scheduler: ScheduledExecutorService
            enabled().run { context ->
                assertThat(context).hasNotFailed()
                val client = ReflectionTestUtils.getField(context.getBean(CloudflareModelFactory::class.java), "client")!!
                scheduler = ReflectionTestUtils.getField(client, "watchdog") as ScheduledExecutorService
                assertThat(scheduler.isShutdown).isFalse()
            }
            assertThat(scheduler.isShutdown).isTrue()
        }
    }

    @Nested
    inner class Binding {
        @Test
        fun `configured catalog and bounded transport options bind`() {
            enabled("$prefix.model-names[0]=@cf/cloudflare/clef-flash", "$prefix.timeout=3s", "$prefix.max-request-bytes=256", "$prefix.max-response-bytes=1024")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    val properties = context.getBean(CloudflareProperties::class.java)
                    assertThat(properties.timeout.seconds).isEqualTo(3)
                    assertThat(properties.maxRequestBytes).isEqualTo(256)
                    assertThat(properties.maxResponseBytes).isEqualTo(1024)
                    assertThat(context.getBean(CloudflareModelFactory::class.java).models().map { it.modelName }).containsExactly("@cf/cloudflare/clef-flash")
                }
        }

        @Test
        fun `enabled invalid account model or option rejects startup`() {
            listOf("account-id=", "account-id=bad", "model-names[0]=unknown", "model-names[0]= ", "model-names[0]=@cf/cloudflare/clef\n$prefix.model-names[1]=@cf/cloudflare/clef",
                "timeout=0s", "timeout=-1s", "timeout=301s", "max-request-bytes=0", "max-request-bytes=1048577", "max-response-bytes=0", "max-response-bytes=1048577")
                .forEach { setting -> enabled(*setting.split("\n").map { if (it.startsWith(prefix)) it else "$prefix.$it" }.toTypedArray())
                    .run { context -> assertThat(context).hasFailed() } }
            runner.withPropertyValues("$prefix.enabled=true").run { context -> assertThat(context).hasFailed() }
        }

        @Test
        fun `validation diagnostics and property display omit configured token`() {
            val token = "PRIVATE_TEST_TOKEN"
            enabled("$prefix.api-key=$token").run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(CloudflareProperties::class.java).toString()).doesNotContain(token)
                assertThat(context.getBean(CloudflareModelFactory::class.java).toString()).doesNotContain(token)
            }
            enabled("$prefix.api-key=$token", "$prefix.account-id=invalid").run { context ->
                assertThat(context).hasFailed()
                val messages = generateSequence(context.startupFailure) { it.cause }.map { it.toString() }.joinToString()
                assertThat(messages).doesNotContain(token)
            }
        }

        @Test
        fun `enabled unknown controls fail strict binding`() {
            listOf("unknown", "base-url", "body-selector", "runtime-path").forEach { key ->
                enabled("$prefix.$key=unused").run { context -> assertThat(context).hasFailed() }
            }
        }
    }
}
