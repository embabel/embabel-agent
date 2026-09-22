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
package com.embabel.common.ai.model.local

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import java.io.IOException

class DiscoveryFailureReporterTest {

    private val logger = mockk<Logger>(relaxed = true)
    private val reporter = DiscoveryFailureReporter(logger)

    @Test
    fun `a runner that stays down warns once, not once per attempt`() {
        repeat(5) { reporter.failed("http://localhost:11434", IOException("Connection refused")) }

        verify(exactly = 1) { logger.warn(any<String>(), any(), any()) }
        verify(exactly = 4) { logger.debug(any<String>(), any(), any()) }
    }

    @Test
    fun `a different failure warns again`() {
        reporter.failed("http://localhost:11434", IOException("Connection refused"))
        reporter.failed("http://localhost:11434", IOException("Read timed out"))

        verify(exactly = 2) { logger.warn(any<String>(), any(), any()) }
    }

    @Test
    fun `endpoints are tracked separately`() {
        reporter.failed("http://a:11434", IOException("Connection refused"))
        reporter.failed("http://b:11434", IOException("Connection refused"))

        verify(exactly = 2) { logger.warn(any<String>(), any(), any()) }
    }

    @Test
    fun `recovery is reported once, and a later failure warns again`() {
        reporter.failed("http://localhost:11434", IOException("Connection refused"))
        reporter.succeeded("http://localhost:11434")
        reporter.succeeded("http://localhost:11434")
        reporter.failed("http://localhost:11434", IOException("Connection refused"))

        verify(exactly = 1) { logger.info(any<String>(), any<Any>()) }
        verify(exactly = 2) { logger.warn(any<String>(), any(), any()) }
    }

    @Test
    fun `a healthy runner logs nothing`() {
        repeat(3) { reporter.succeeded("http://localhost:11434") }

        verify(exactly = 0) { logger.info(any<String>(), any<Any>()) }
        verify(exactly = 0) { logger.warn(any<String>(), any(), any()) }
    }
}
