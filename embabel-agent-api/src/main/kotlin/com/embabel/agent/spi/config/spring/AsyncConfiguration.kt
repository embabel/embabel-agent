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

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.spi.support.ExecutorAsyncer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import jakarta.annotation.PreDestroy
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Configures async execution for the Embabel agent platform.
 *
 * Threading model based on:
 * 1. Application's spring.threads.virtual.enabled property (inheritance)
 * 2. embabel.agent.platform.virtual.threads.override (flip threading model)
 * 3. embabel.agent.platform.threading.shared (share executor when models match)
 */
@Configuration
class AsyncConfiguration(
    private val properties: AgentPlatformProperties,
    @Value("\${spring.threads.virtual.enabled:false}")
    private val appUsesVirtualThreads: Boolean
) {

    companion object {
        private val logger = LoggerFactory.getLogger(AsyncConfiguration::class.java)
        private const val JAVA_21_MAJOR_VERSION = 21

        // Threading model names
        private const val VIRTUAL_THREAD_MODEL = "virtual"
        private const val PLATFORM_THREAD_MODEL = "platform"

        // Thread name prefixes
        private const val VIRTUAL_THREAD_NAME_PREFIX = "embabel-virtual-"
        private const val PLATFORM_THREAD_NAME_PREFIX = "embabel-platform-"
    }

    /**
     * Tracks executor created by Embabel (for shutdown).
     * Null when sharing app's executor.
     */
    private var embabelOwnedExecutor: ExecutorService? = null

    /**
     * Creates the Asyncer abstraction for Embabel agent operations.
     *
     * Threading model determination:
     * 1. Inherit app's threading model (virtual vs platform)
     * 2. Apply override if configured (flips the choice)
     * 3. Share app's executor when threading models match + sharing enabled
     * 4. Otherwise create isolated raw JDK executor (no Spring lifecycle management)
     */
    @Bean
    fun asyncer(
        @Qualifier(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
        applicationExecutor: ObjectProvider<Executor>
    ): Asyncer {
        // Step 1 & 2: Determine Embabel's threading model (inherit + override)
        val embabelUsesVirtual = shouldUseVirtualThreads()

        // Step 3: Attempt to get shared executor if enabled
        val sharedExecutor = if (shouldUseSharedExecutor(embabelUsesVirtual)) {
            applicationExecutor.getIfAvailable()
        } else {
            null
        }

        // Use shared executor if available, otherwise create isolated
        val executor = if (sharedExecutor != null) {
            val threadModel = if (embabelUsesVirtual) VIRTUAL_THREAD_MODEL else PLATFORM_THREAD_MODEL
            logger.info("Sharing app's $threadModel executor (models match, shared=true)")
            sharedExecutor
        } else {
            // Step 4: Create isolated raw JDK executor (no Spring lifecycle)
            createExecutorWithLogging(embabelUsesVirtual).also {
                embabelOwnedExecutor = it as? ExecutorService
            }
        }

        return ExecutorAsyncer(executor)
    }

    /**
     * Creates executor with appropriate logging.
     *
     * Three scenarios:
     * 1. Embabel uses virtual + Java 21+ → virtual threads
     * 2. Embabel uses virtual + Java < 21 → platform threads (fallback)
     * 3. Embabel uses platform → platform threads
     */
    private fun createExecutorWithLogging(embabelUsesVirtual: Boolean): Executor {
        val useVirtual = embabelUsesVirtual && isJava21Plus()
        val isFallbackToPlatform = embabelUsesVirtual && !isJava21Plus()
        val isPlatformRequested = !embabelUsesVirtual

        when {
            isFallbackToPlatform -> logger.info("Creating $PLATFORM_THREAD_MODEL executor (fallback from $VIRTUAL_THREAD_MODEL, Java < 21)")
            useVirtual -> logger.info("Creating $VIRTUAL_THREAD_MODEL executor")
            isPlatformRequested -> logger.info("Creating $PLATFORM_THREAD_MODEL executor")
        }

        return createExecutor(useVirtual = useVirtual)
    }

    /**
     * Determines if Embabel should use virtual threads.
     *
     * Logic: Inherit from app, then flip if override is enabled.
     */
    private fun shouldUseVirtualThreads(): Boolean =
        if (properties.threading.override) {
            !appUsesVirtualThreads  // Flip: virtual → platform or platform → virtual
        } else {
            appUsesVirtualThreads   // Inherit: same as app
        }

    /**
     * Checks if should use shared executor with application.
     *
     * Sharing applies when both app and Embabel use the same threading model
     * (either platform/platform or virtual/virtual).
     */
    private fun shouldUseSharedExecutor(embabelUsesVirtual: Boolean): Boolean =
        (embabelUsesVirtual == appUsesVirtualThreads) && properties.threading.shared

    /**
     * Creates a raw JDK executor (no Spring lifecycle management).
     *
     * Uses custom thread factories for observability:
     * - Virtual threads: "embabel-virtual-N"
     * - Platform threads: "embabel-platform-N" with CachedThreadPool behavior
     *
     * Raw JDK executors avoid Spring's SmartLifecycle 30-second shutdown timeout.
     */
    private fun createExecutor(useVirtual: Boolean): Executor =
        if (useVirtual && isJava21Plus()) {
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                    .name(VIRTUAL_THREAD_NAME_PREFIX, 0)
                    .factory()
            )
        } else {
            Executors.newCachedThreadPool(
                Thread.ofPlatform()
                    .name(PLATFORM_THREAD_NAME_PREFIX, 0)
                    .factory()
            )
        }

    /**
     * Checks if running on Java 21+ (virtual threads support).
     */
    internal fun isJava21Plus(): Boolean {
        val version = System.getProperty("java.version")
        val majorVersion = version.substringBefore('.').toIntOrNull()
            ?: version.substringBefore('-').toIntOrNull()
            ?: return false
        return majorVersion >= JAVA_21_MAJOR_VERSION
    }

    /**
     * Shuts down executor created by Embabel.
     * Does nothing when sharing app's executor.
     */
    @PreDestroy
    fun shutdownOwnedExecutor() {
        embabelOwnedExecutor?.shutdown()
    }
}
