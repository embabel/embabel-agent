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
import com.embabel.agent.api.annotation.LlmTool
import org.slf4j.LoggerFactory

/**
 * Returns the WARN messages that the logger named [loggerName] logs while [block] runs.
 */
fun captureWarnings(loggerName: String, block: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger(loggerName) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    // Make sure WARN is enabled for this logger, whatever the logging configuration says.
    val originalLevel = logger.level
    if (!logger.isWarnEnabled) logger.level = Level.WARN
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
        logger.level = originalLevel
    }
    return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
}

/** A tool class with a method named `lookup`, for name collision tests. */
class LookupToolsA {
    @LlmTool(description = "Look up in A")
    fun lookup(): String = "a"
}

/** An open tool class, so that Spring can make a CGLIB proxy of it. */
open class ProxiedLookupTools {
    @LlmTool(description = "Look up behind a proxy")
    open fun lookup(): String = "p"
}

/** Another tool class with a method named `lookup`, for name collision tests. */
class LookupToolsB {
    @LlmTool(description = "Look up in B")
    fun lookup(): String = "b"
}
