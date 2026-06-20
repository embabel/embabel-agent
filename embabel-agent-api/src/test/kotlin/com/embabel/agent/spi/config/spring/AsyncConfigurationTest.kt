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

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.support.ExecutorAsyncer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.ObjectProvider
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive tests for AsyncConfiguration covering:
 * 1. Threading model inheritance and override logic
 * 2. Executor sharing when threading models match (platform/platform AND virtual/virtual)
 * 3. Isolated raw JDK executors (no Spring lifecycle management)
 * 4. ThreadLocal propagation (AgentProcess, Micrometer Observation)
 * 5. Thread naming conventions
 * 6. Java version detection and fallback
 */
class AsyncConfigurationTest {

    @AfterEach
    fun cleanup() {
        AgentProcess.remove()
    }

    @Nested
    inner class ThreadingModelTests {

        @Test
        fun `should use virtual threads when app uses virtual and override is false - inheritance`() {
            val config = createConfig(appUsesVirtual = true, override = false)
            val asyncer = config.asyncer(emptyAppExecutor())

            if (config.isJava21Plus()) {
                assertVirtualThreads(asyncer)
            } else {
                assertPlatformThreads(asyncer)
            }
        }

        @Test
        fun `should use platform threads when app uses virtual and override is true - flip to platform`() {
            val config = createConfig(appUsesVirtual = true, override = true)
            val asyncer = config.asyncer(emptyAppExecutor())

            assertPlatformThreads(asyncer)
        }

        @Test
        fun `should use platform threads when app uses platform and override is false - inheritance`() {
            val config = createConfig(appUsesVirtual = false, override = false)
            val asyncer = config.asyncer(emptyAppExecutor())

            assertPlatformThreads(asyncer)
        }

        @Test
        fun `should use virtual threads when app uses platform and override is true - flip to virtual`() {
            val config = createConfig(appUsesVirtual = false, override = true)
            val asyncer = config.asyncer(emptyAppExecutor())

            if (config.isJava21Plus()) {
                assertVirtualThreads(asyncer)
            } else {
                assertPlatformThreads(asyncer)
            }
        }
    }

    @Nested
    inner class ExecutorSharingTests {

        @Test
        fun `should share app executor when both use platform and shared is true`() {
            val appExecutor = Executors.newCachedThreadPool()
            val config = createConfig(appUsesVirtual = false, override = false, shared = true)

            try {
                val asyncer = config.asyncer(providedAppExecutor(appExecutor))
                val usedExecutor = extractExecutor(asyncer)
                assertThat(usedExecutor).isSameAs(appExecutor)
            } finally {
                appExecutor.shutdown()
            }
        }

        @Test
        fun `should share app executor when both use virtual and shared is true`() {
            val config = createConfig(appUsesVirtual = true, override = false, shared = true)
            if (!config.isJava21Plus()) {
                println("Skipping virtual thread test on Java < 21")
                return
            }

            val appExecutor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val asyncer = config.asyncer(providedAppExecutor(appExecutor))
                val usedExecutor = extractExecutor(asyncer)
                assertThat(usedExecutor).isSameAs(appExecutor)
            } finally {
                appExecutor.shutdown()
            }
        }

        @Test
        fun `should not share executor when app uses virtual but embabel uses platform`() {
            val appExecutor = Executors.newCachedThreadPool()
            val config = createConfig(appUsesVirtual = true, override = true, shared = true)

            try {
                val asyncer = config.asyncer(providedAppExecutor(appExecutor))
                val usedExecutor = extractExecutor(asyncer)
                assertThat(usedExecutor).isNotSameAs(appExecutor)
            } finally {
                appExecutor.shutdown()
            }
        }

        @Test
        fun `should not share executor when app uses platform but embabel uses virtual`() {
            val appExecutor = Executors.newCachedThreadPool()
            val config = createConfig(appUsesVirtual = false, override = true, shared = true)

            try {
                val asyncer = config.asyncer(providedAppExecutor(appExecutor))
                val usedExecutor = extractExecutor(asyncer)
                assertThat(usedExecutor).isNotSameAs(appExecutor)
            } finally {
                appExecutor.shutdown()
            }
        }

        @Test
        fun `should create isolated executor when platform-platform but shared is false`() {
            val appExecutor = Executors.newCachedThreadPool()
            val config = createConfig(appUsesVirtual = false, override = false, shared = false)

            try {
                val asyncer = config.asyncer(providedAppExecutor(appExecutor))
                val usedExecutor = extractExecutor(asyncer)
                assertThat(usedExecutor).isNotSameAs(appExecutor)
            } finally {
                appExecutor.shutdown()
            }
        }

        @Test
        fun `should create isolated executor when virtual-virtual but shared is false`() {
            val config = createConfig(appUsesVirtual = true, override = false, shared = false)
            if (!config.isJava21Plus()) {
                println("Skipping virtual thread test on Java < 21")
                return
            }

            val appExecutor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val asyncer = config.asyncer(providedAppExecutor(appExecutor))
                val usedExecutor = extractExecutor(asyncer)
                assertThat(usedExecutor).isNotSameAs(appExecutor)
            } finally {
                appExecutor.shutdown()
            }
        }

        @Test
        fun `should create fallback platform executor when app executor unavailable and shared is true`() {
            val config = createConfig(appUsesVirtual = false, override = false, shared = true)
            val asyncer = config.asyncer(emptyAppExecutor())

            assertPlatformThreads(asyncer)
        }
    }

    @Nested
    inner class ThreadLocalPropagationTests {

        @Test
        fun `AgentProcess should propagate through virtual executor`() {
            val config = createConfig(appUsesVirtual = true, override = false)
            if (!config.isJava21Plus()) {
                println("Skipping virtual thread test on Java < 21")
                return
            }

            val asyncer = config.asyncer(emptyAppExecutor())

            val testProcess = mock(AgentProcess::class.java)
            AgentProcess.set(testProcess)

            val result = asyncer.async {
                AgentProcess.get()
            }.get(5, TimeUnit.SECONDS)

            assertEquals(testProcess, result)
        }

        @Test
        fun `AgentProcess should propagate through platform executor`() {
            val config = createConfig(appUsesVirtual = false, override = false)
            val asyncer = config.asyncer(emptyAppExecutor())

            val testProcess = mock(AgentProcess::class.java)
            AgentProcess.set(testProcess)

            val result = asyncer.async {
                AgentProcess.get()
            }.get(5, TimeUnit.SECONDS)

            assertEquals(testProcess, result)
        }

        @Test
        fun `Micrometer Observation should propagate through virtual executor`() {
            val config = createConfig(appUsesVirtual = true, override = false)
            if (!config.isJava21Plus()) {
                println("Skipping virtual thread test on Java < 21")
                return
            }

            testObservationPropagation(config)
        }

        @Test
        fun `Micrometer Observation should propagate through platform executor`() {
            val config = createConfig(appUsesVirtual = false, override = false)
            testObservationPropagation(config)
        }
    }

    @Nested
    inner class ExecutorConfigurationTests {

        @Test
        fun `asyncer bean should wrap executor with ExecutorAsyncer`() {
            val config = createConfig(appUsesVirtual = false, override = false)
            val asyncer = config.asyncer(emptyAppExecutor())

            assertThat(asyncer).isInstanceOf(ExecutorAsyncer::class.java)
        }

        @Test
        fun `virtual executor should create threads with embabel-virtual prefix`() {
            val config = createConfig(appUsesVirtual = true, override = false)
            if (!config.isJava21Plus()) {
                println("Skipping virtual thread test on Java < 21")
                return
            }

            val asyncer = config.asyncer(emptyAppExecutor())
            val threadName = asyncer.async {
                Thread.currentThread().name
            }.get(5, TimeUnit.SECONDS)

            assertThat(threadName).startsWith("embabel-virtual-")
        }

        @Test
        fun `virtual executor should create actual virtual threads`() {
            val config = createConfig(appUsesVirtual = true, override = false)
            if (!config.isJava21Plus()) {
                println("Skipping virtual thread test on Java < 21")
                return
            }

            val asyncer = config.asyncer(emptyAppExecutor())
            assertVirtualThreads(asyncer)
        }

        @Test
        fun `platform executor should create threads with embabel-platform prefix`() {
            val config = createConfig(appUsesVirtual = false, override = false)
            val asyncer = config.asyncer(emptyAppExecutor())

            val threadName = asyncer.async {
                Thread.currentThread().name
            }.get(5, TimeUnit.SECONDS)

            assertThat(threadName).startsWith("embabel-platform-")
        }
    }

    // ==================== Helper Methods ====================

    private fun createConfig(
        appUsesVirtual: Boolean,
        override: Boolean = false,
        shared: Boolean = false
    ): AsyncConfiguration {
        val properties = AgentPlatformProperties()
        properties.threading.override = override
        properties.threading.shared = shared
        return AsyncConfiguration(properties, appUsesVirtual)
    }

    private fun emptyAppExecutor(): ObjectProvider<Executor> {
        return object : ObjectProvider<Executor> {
            override fun getObject(): Executor = throw NoSuchElementException("No executor available")
            override fun getObject(vararg args: Any?): Executor = throw NoSuchElementException("No executor available")
            override fun getIfAvailable(): Executor? = null
            override fun getIfUnique(): Executor? = null
            override fun iterator(): MutableIterator<Executor> = mutableListOf<Executor>().iterator()
            override fun orderedStream(): java.util.stream.Stream<Executor> = java.util.stream.Stream.empty()
            override fun stream(): java.util.stream.Stream<Executor> = java.util.stream.Stream.empty()
        }
    }

    private fun providedAppExecutor(executor: Executor): ObjectProvider<Executor> {
        return object : ObjectProvider<Executor> {
            override fun getObject(): Executor = executor
            override fun getObject(vararg args: Any?): Executor = executor
            override fun getIfAvailable(): Executor = executor
            override fun getIfUnique(): Executor = executor
            override fun iterator(): MutableIterator<Executor> = mutableListOf(executor).iterator()
            override fun orderedStream(): java.util.stream.Stream<Executor> = java.util.stream.Stream.of(executor)
            override fun stream(): java.util.stream.Stream<Executor> = java.util.stream.Stream.of(executor)
        }
    }

    private fun extractExecutor(asyncer: com.embabel.agent.api.common.Asyncer): Executor {
        // Access the private executor field via reflection
        val field = ExecutorAsyncer::class.java.getDeclaredField("executor")
        field.isAccessible = true
        return field.get(asyncer) as Executor
    }

    private fun assertVirtualThreads(asyncer: com.embabel.agent.api.common.Asyncer) {
        val isVirtual = asyncer.async {
            Thread.currentThread().isVirtual
        }.get(5, TimeUnit.SECONDS)

        assertTrue(isVirtual, "Executor should create virtual threads")
    }

    private fun assertPlatformThreads(asyncer: com.embabel.agent.api.common.Asyncer) {
        val isVirtual = asyncer.async {
            Thread.currentThread().isVirtual
        }.get(5, TimeUnit.SECONDS)

        assertThat(isVirtual).isFalse
    }

    private fun testObservationPropagation(config: AsyncConfiguration) {
        val asyncer = config.asyncer(emptyAppExecutor())

        val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(object : ObservationHandler<Observation.Context> {
                override fun supportsContext(context: Observation.Context) = true
            })
        }
        ObservationThreadLocalAccessor.getInstance().observationRegistry = registry

        try {
            val parent = Observation.start("parent", registry)
            parent.openScope().use {
                val seenOnWorker = asyncer.async {
                    registry.currentObservation
                }.get(5, TimeUnit.SECONDS)

                assertEquals(parent, seenOnWorker, "Observation should propagate across threads")
            }
            parent.stop()
        } finally {
            ObservationThreadLocalAccessor.getInstance().observationRegistry = ObservationRegistry.NOOP
        }
    }
}
