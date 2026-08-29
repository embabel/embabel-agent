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
package com.embabel.agent.core.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class Named(val name: String, val meaning: String)

/**
 * The de-duplication itself is not new — the platform has always collapsed name-keyed
 * views. What is new is that a collapse which loses a distinct element says so.
 */
class NameCollisionsTest {

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun attachAppender() {
        logger = LoggerFactory.getLogger("com.embabel.agent.core.support.NameCollisions") as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        logger.level = Level.ERROR
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun errors(): List<String> =
        appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage }

    private fun unique(suffix: String) = "collision-test-$suffix-${System.nanoTime()}"

    @Nested
    inner class Reports {

        @Test
        fun `two different elements sharing a name are reported`() {
            val name = unique("different")
            val kept = listOf(
                Named(name, "what A means"),
                Named(name, "what B means"),
            ).distinctByNameReportingCollisions(kind = "goal") { it.name }

            assertEquals(1, kept.size, "One is still dropped: the view is keyed by name")
            assertEquals(1, errors().size, "The loss must be reported")
            assertTrue(
                errors().single().contains(name),
                "The report must name the offending element: ${errors()}",
            )
            assertTrue(
                errors().single().contains("reduced 2 candidate entries to 1 published entries"),
                "The report must include the size reduction: ${errors()}",
            )
        }

        @Test
        fun `reports collision context and retained and dropped elements`() {
            val name = unique("details")
            listOf(
                Named(name, "retained meaning"),
                Named(name, "dropped meaning"),
            ).distinctByNameReportingCollisions(
                kind = "tool",
                context = "Agent.action",
                describe = { it.meaning },
            ) { it.name }

            val error = errors().single()
            assertTrue(error.contains("Agent.action"), error)
            assertTrue(error.contains("retained meaning"), error)
            assertTrue(error.contains("dropped meaning"), error)
        }

        @Test
        fun `reports every distinct element after the retained one in a three-way collision`() {
            val name = unique("three-way")
            val kept = listOf(
                Named(name, "retained meaning"),
                Named(name, "first dropped meaning"),
                Named(name, "second dropped meaning"),
            ).distinctByNameReportingCollisions(kind = "goal") { it.name }

            assertEquals(1, kept.size)
            val error = errors().single()
            assertTrue(error.contains("first dropped meaning"), error)
            assertTrue(error.contains("second dropped meaning"), error)
        }

        @Test
        fun `a collision is reported once, not on every pass`() {
            val name = unique("hot-path")
            val elements = listOf(Named(name, "A"), Named(name, "B"))

            repeat(5) { elements.distinctByNameReportingCollisions(kind = "goal") { it.name } }

            assertEquals(
                1,
                errors().size,
                "Aggregated views are recomputed on every read; reporting must not repeat",
            )
        }

        @Test
        fun `elements without value semantics are compared as the caller asks`() {
            val name = unique("identity")
            val one = Named(name, "same")
            val two = Named(name, "same")

            listOf(one, two).distinctByNameReportingCollisions(
                kind = "tool",
                sameValue = { a, b -> a === b },
            ) { it.name }

            assertEquals(
                1,
                errors().size,
                "Equal by value but distinct instances: identity comparison must still report",
            )
        }
    }

    @Nested
    inner class StaysQuiet {

        @Test
        fun `the same element declared twice is not a collision`() {
            val name = unique("identical")
            val kept = listOf(
                Named(name, "one meaning, agreed by both"),
                Named(name, "one meaning, agreed by both"),
            ).distinctByNameReportingCollisions(kind = "goal") { it.name }

            assertEquals(1, kept.size)
            assertTrue(errors().isEmpty(), "Nothing is lost, so nothing to report: ${errors()}")
        }

        @Test
        fun `distinct names are all kept and nothing is reported`() {
            val a = unique("a")
            val b = unique("b")
            val kept = listOf(Named(a, "A"), Named(b, "B"))
                .distinctByNameReportingCollisions(kind = "goal") { it.name }

            assertEquals(listOf(a, b), kept.map { it.name })
            assertTrue(errors().isEmpty())
        }

        @Test
        fun `first declaration wins, as before`() {
            val name = unique("order")
            val kept = listOf(Named(name, "first"), Named(name, "second"))
                .distinctByNameReportingCollisions(kind = "goal") { it.name }

            assertEquals(
                "first",
                kept.single().meaning,
                "distinctBy kept the first; that behaviour is unchanged",
            )
        }
    }
}
