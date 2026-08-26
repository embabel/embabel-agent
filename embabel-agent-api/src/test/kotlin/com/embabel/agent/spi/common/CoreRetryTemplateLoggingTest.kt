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
package com.embabel.agent.spi.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.core.NonRetryable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The three core.retry providers (deepseek, mistral-ai, google-genai) used to run silently: their
 * template carried a policy and no listener, so a rate limit, a give-up and an exhaustion all
 * passed unlogged. These tests hold the core.retry path to the same account as the spring-retry
 * one in [RetryPropertiesLoggingTest]: announce a retry only when one follows, name the configured
 * ceiling, and claim exhaustion only when the attempts really ran out.
 */
class CoreRetryTemplateLoggingTest {

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MODEL = "mistral-small-2603"
        const val PREFIX = "embabel.agent.platform.models.test"
    }

    /** A rate limited failure that is nevertheless marked as never worth retrying. */
    private class NonRetryableRateLimit(message: String) : RuntimeException(message), NonRetryable

    private class TestRetryProperties(override val maxAttempts: Int) : RetryProperties {
        override val backoffMillis = 1L
        override val backoffMultiplier = 2.0
        override val backoffMaxInterval = 5L
        override val propertyPrefix = PREFIX
    }

    private lateinit var logger: Logger
    private lateinit var appender: ListAppender<ILoggingEvent>
    private var previousLevel: Level? = null

    @BeforeEach
    fun captureLogs() {
        logger = LoggerFactory.getLogger(RetryProperties::class.java) as Logger
        previousLevel = logger.level
        logger.level = Level.INFO
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun releaseLogs() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }

    /** What the loop actually did, alongside what it said about it. */
    private class Outcome(val attempts: Int, events: List<ILoggingEvent>) {
        val retries: Int get() = attempts - 1
        val messages: List<String> = events.map { it.formattedMessage }
        val warnings: List<String> = events.filter { it.level == Level.WARN }.map { it.formattedMessage }

        /** Lines that tell the operator a retry is happening. */
        val retryAnnouncements: List<String> get() = messages.filter { it.contains("retry attempt", ignoreCase = true) }
    }

    private fun failWith(error: Throwable, maxAttempts: Int = MAX_ATTEMPTS): Outcome {
        val attempts = AtomicInteger()
        val template = TestRetryProperties(maxAttempts).coreRetryTemplate(MODEL)
        try {
            template.execute<Any> {
                attempts.incrementAndGet()
                throw error
            }
        } catch (expected: Throwable) {
            // core.retry wraps the last failure in a RetryException; the logs are what we are testing
        }
        return Outcome(attempts.get(), appender.list.toList())
    }

    @Test
    fun `a retry is announced only when a retry follows`() {
        val outcome = failWith(NonTransientAiException("429 - Rate limit reached for $MODEL"))

        assertThat(outcome.attempts).describedAs("attempts made").isEqualTo(MAX_ATTEMPTS)
        assertThat(outcome.retryAnnouncements)
            .describedAs("one announcement per retry actually performed, in %s", outcome.messages)
            .hasSize(outcome.retries)
    }

    @Test
    fun `a rate limit is named as one`() {
        val outcome = failWith(NonTransientAiException("429 - Rate limit reached for $MODEL"))

        assertThat(outcome.retryAnnouncements)
            .describedAs("the operator must be able to tell a rate limit from any other failure")
            .allMatch { it.contains("RATE LIMITED") }
            .describedAs("and to tell which of the twelve providers is being throttled")
            .allMatch { it.contains(MODEL) }
    }

    @Test
    fun `no retry is announced when the failure is not retryable`() {
        val outcome = failWith(NonRetryableRateLimit("429 - Rate limit reached, and marked non retryable"))

        assertThat(outcome.attempts).describedAs("attempts made").isEqualTo(1)
        assertThat(outcome.retryAnnouncements)
            .describedAs("nothing was retried, so nothing may claim a retry, in %s", outcome.messages)
            .isEmpty()
        assertThat(outcome.messages)
            .describedAs("but the give-up must still be reported, or the run looks silent")
            .anyMatch { it.contains("not retrying") }
    }

    @Test
    fun `the announced ceiling is the configured maxAttempts`() {
        val outcome = failWith(NonTransientAiException("429 - Rate limit reached for $MODEL"))

        assertThat(outcome.messages)
            .describedAs("core.retry counts retries, the operator configures attempts")
            .noneMatch { it.contains("unknown") }
        assertThat(outcome.messages)
            .describedAs("the configured ceiling must appear")
            .anyMatch { it.contains("of $MAX_ATTEMPTS") }
    }

    @Test
    fun `exhaustion is not claimed when the attempts were not exhausted`() {
        // The predicate rejects a malformed request on the first failure, yet core.retry still
        // calls onRetryPolicyExhaustion. One attempt out of three: nothing is exhausted.
        val outcome = failWith(IllegalArgumentException("Unknown model 'mistral-typo'"))

        assertThat(outcome.attempts).describedAs("attempts made").isEqualTo(1)
        assertThat(outcome.warnings)
            .describedAs("%d of %d attempts used, in %s", outcome.attempts, MAX_ATTEMPTS, outcome.warnings)
            .noneMatch { it.contains("Maximum attempts") }
    }

    @Test
    fun `exhaustion is still reported when the attempts really are exhausted`() {
        val outcome = failWith(TransientAiException("503 - Upstream temporarily unavailable"))

        assertThat(outcome.attempts).describedAs("attempts made").isEqualTo(MAX_ATTEMPTS)
        assertThat(outcome.warnings)
            .describedAs("the ceiling was genuinely hit, the operator must be told")
            .anyMatch { it.contains("Maximum attempts of $MAX_ATTEMPTS") }
        assertThat(outcome.warnings)
            .describedAs("and told which property to raise")
            .anyMatch { it.contains("$PREFIX.max-attempts") }
        assertThat(outcome.warnings)
            .describedAs("this WARN is often the only line an operator sees, so it must name the call")
            .allMatch { it.contains(MODEL) }
    }
}
