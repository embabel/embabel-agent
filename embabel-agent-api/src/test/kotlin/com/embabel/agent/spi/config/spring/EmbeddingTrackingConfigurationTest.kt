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

import com.embabel.agent.api.event.EmbeddingEvent
import com.embabel.agent.api.event.EmbeddingEventListener
import com.embabel.agent.spi.support.embedding.EmbeddingOperations
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order

/**
 * NOTE on bean naming: every `@Bean` method across the `@TestConfiguration` classes
 * declared in this file MUST have a unique name. `AgentApiTestApplication` performs
 * an explicit `@ComponentScan(basePackages = "com.embabel.agent")` which discovers
 * `@TestConfiguration` classes and tries to register all their beans into any test
 * that boots the full application context — duplicate names cause
 * `BeanDefinitionOverrideException` and propagate failures across unrelated e2e tests.
 */
class EmbeddingTrackingConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(EmbeddingTrackingConfiguration::class.java)

    private class FakeEmbeddingService(override val name: String = "fake") : EmbeddingService {
        override val provider: String = "test"
        override val pricingModel: PricingModel? = null
        override val dimensions: Int = 1
        override fun embed(text: String): FloatArray = FloatArray(1)
        override fun embed(texts: List<String>): List<FloatArray> = texts.map { FloatArray(1) }
    }

    @TestConfiguration
    open class OneEmbeddingConfig {
        @Bean
        open fun embeddingService(): EmbeddingService = FakeEmbeddingService("only")
    }

    @TestConfiguration
    open class TwoEmbeddingsConfig {
        @Bean
        open fun first(): EmbeddingService = FakeEmbeddingService("first")

        @Bean
        open fun second(): EmbeddingService = FakeEmbeddingService("second")
    }

    private class RecordingListener : EmbeddingEventListener {
        val events = mutableListOf<EmbeddingEvent>()
        override fun onEmbeddingEvent(event: EmbeddingEvent) {
            events += event
        }
    }

    @TestConfiguration
    open class WithSingleListenerConfig {
        @Bean
        open fun listenerEmbeddingService(): EmbeddingService = FakeEmbeddingService("with-listener")

        @Bean
        open fun listener(): EmbeddingEventListener = RecordingListener()
    }

    @TestConfiguration
    open class WithMultipleListenersConfig {
        @Bean
        open fun multiListenerEmbeddingService(): EmbeddingService = FakeEmbeddingService("with-multi-listener")

        @Bean
        open fun listenerA(): EmbeddingEventListener = RecordingListener()

        @Bean
        open fun listenerB(): EmbeddingEventListener = RecordingListener()
    }

    /** Records the dispatch order on a shared sink so the test can assert listener ordering. */
    private class TaggingListener(private val tag: String, private val sink: MutableList<String>) :
        EmbeddingEventListener {
        override fun onEmbeddingEvent(event: EmbeddingEvent) {
            sink += tag
        }
    }

    @TestConfiguration
    open class WithOrderedListenersConfig {
        // Shared sink so we can verify the relative order of listener invocations.
        val invocationOrder: MutableList<String> = mutableListOf()

        @Bean
        open fun orderedListenersEmbeddingService(): EmbeddingService = FakeEmbeddingService("ordered")

        @Bean
        @Order(2)
        open fun secondListener(): EmbeddingEventListener = TaggingListener("second", invocationOrder)

        @Bean
        @Order(1)
        open fun firstListener(): EmbeddingEventListener = TaggingListener("first", invocationOrder)
    }

    @Test
    fun `wraps a single EmbeddingService bean in EmbeddingOperations`() {
        runner.withUserConfiguration(OneEmbeddingConfig::class.java).run { ctx ->
            val bean = ctx.getBean(EmbeddingService::class.java)
            assertTrue(bean is EmbeddingOperations, "Expected EmbeddingOperations, got ${bean::class.qualifiedName}")
        }
    }

    @Test
    fun `wraps every EmbeddingService bean independently when multiple are present`() {
        runner.withUserConfiguration(TwoEmbeddingsConfig::class.java).run { ctx ->
            val beans = ctx.getBeansOfType(EmbeddingService::class.java)
            assertTrue(beans.values.all { it is EmbeddingOperations })
            assertNotSame(beans["first"], beans["second"])
        }
    }

    @Test
    fun `does not double-wrap an already-wrapped EmbeddingOperations bean`() {
        // Directly invoke the BeanPostProcessor on an already-wrapped instance —
        // calling getBean() twice would only test Spring's singleton cache, not the
        // `if (bean is EmbeddingOperations) return bean` short-circuit.
        runner.withUserConfiguration(OneEmbeddingConfig::class.java).run { ctx ->
            val bpp = ctx.getBean("embeddingTrackingBeanPostProcessor", BeanPostProcessor::class.java)
            val alreadyWrapped = EmbeddingOperations(FakeEmbeddingService("pre-wrapped"))
            val result = bpp.postProcessAfterInitialization(alreadyWrapped, "any")
            assertSame(alreadyWrapped, result, "BPP must return the same instance, not re-wrap")
        }
    }

    @Test
    fun `wraps with NOOP listener when no EmbeddingEventListener bean is present`() {
        // No listener bean → embed() must still work without errors.
        runner.withUserConfiguration(OneEmbeddingConfig::class.java).run { ctx ->
            val bean = ctx.getBean(EmbeddingService::class.java)
            // Should not throw — NOOP listener absorbs events.
            bean.embed("hello")
        }
    }

    @Test
    fun `injects single EmbeddingEventListener into wrapped EmbeddingOperations`() {
        runner.withUserConfiguration(WithSingleListenerConfig::class.java).run { ctx ->
            val embeddingService = ctx.getBean(EmbeddingService::class.java)
            val listener = ctx.getBean(EmbeddingEventListener::class.java) as RecordingListener
            embeddingService.embed("hello")
            // Request + Response events expected (no model-call since FakeEmbeddingService is not Spring AI).
            assertTrue(listener.events.size >= 2, "Listener should receive at least request + response events")
        }
    }

    @Test
    fun `aggregates multiple EmbeddingEventListener beans into a multicast`() {
        runner.withUserConfiguration(WithMultipleListenersConfig::class.java).run { ctx ->
            val embeddingService = ctx.getBean(EmbeddingService::class.java)
            val listeners = ctx.getBeansOfType(EmbeddingEventListener::class.java).values
                .map { it as RecordingListener }
            embeddingService.embed("hello")
            // Both listeners should have received the same events.
            assertEquals(2, listeners.size)
            val firstSize = listeners.first().events.size
            assertTrue(firstSize > 0, "Listeners should receive events")
            listeners.forEach { assertEquals(firstSize, it.events.size, "All listeners must receive every event") }
        }
    }

    @Test
    fun `dispatches EmbeddingEvent to listeners in @Order priority`() {
        runner.withUserConfiguration(WithOrderedListenersConfig::class.java).run { ctx ->
            val embeddingService = ctx.getBean(EmbeddingService::class.java)
            val orderedConfig = ctx.getBean(WithOrderedListenersConfig::class.java)
            embeddingService.embed("hello")
            // 2 listeners × 2 events (request + response) = 4 dispatches
            // expected order: first, second, first, second
            assertEquals(
                listOf("first", "second", "first", "second"),
                orderedConfig.invocationOrder,
                "Listeners must be invoked in @Order ascending priority",
            )
        }
    }
}
