/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.spi.InvalidLlmReturnFormatException
import org.slf4j.LoggerFactory
import org.springframework.ai.converter.StructuredOutputConverter

/**
 * Wrap in our exception handling to capture return string
 */
class ExceptionWrappingConverter<T>(
    private val expectedType: Class<T>,
    private val delegate: StructuredOutputConverter<T>,
) : StructuredOutputConverter<T> {
    private val logger = LoggerFactory.getLogger(ExceptionWrappingConverter::class.java)

    override fun convert(source: String): T? {
        logger.debug("Raw LLM output: {}", source)
        return try {
            delegate.convert(source)
        } catch (e: Exception) {
            logger.warn("Error {} converting LLM output: {}", e.message, source)
            throw InvalidLlmReturnFormatException(
                llmReturn = source,
                expectedType = expectedType,
                cause = e,
            )
        }
    }

    override fun getFormat(): String? = delegate.format
}
