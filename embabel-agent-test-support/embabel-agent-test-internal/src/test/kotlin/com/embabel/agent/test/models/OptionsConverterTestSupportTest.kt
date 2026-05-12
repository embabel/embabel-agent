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
package com.embabel.agent.test.models

import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import org.junit.jupiter.api.Test
import org.springframework.ai.model.tool.ToolCallingChatOptions
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Unit tests for [OptionsConverterTestSupport].
 */
class OptionsConverterTestSupportTest {

    /**
     * Minimal converter used to satisfy the support contract under test.
     *
     * It deliberately preserves only the core values asserted by
     * [checkOptionsConverterPreservesCoreValues]: `temperature` and `topP`.
     */
    private val fakeConverter = OptionsConverter { options ->
        ToolCallingChatOptions.builder()
            .temperature(options.temperature)
            .topP(options.topP)
            .build()
    }

    /**
     * Concrete test-only subclass that exposes the protected converter reference so the
     * support class itself can be verified directly.
     */
    private class FakeOptionsConverterSupport(
        optionsConverter: OptionsConverter<ToolCallingChatOptions>,
    ) : OptionsConverterTestSupport<ToolCallingChatOptions>(optionsConverter) {
        fun exposedConverter(): OptionsConverter<ToolCallingChatOptions> = optionsConverter
    }

    @Test
    fun `support stores provided options converter`() {
        // Arrange
        val support = FakeOptionsConverterSupport(fakeConverter)

        // Act
        val converter = support.exposedConverter()

        // Assert
        assertSame(fakeConverter, converter)
    }

    @Test
    fun `inherited core values test delegates to supplied converter`() {
        // Arrange
        val support = FakeOptionsConverterSupport(fakeConverter)

        // Act
        support.`should preserve core values`()

        // Assert
        val converted = fakeConverter.convertOptions(LlmOptions().withTemperature(0.5).withTopP(0.2))
        assertEquals(0.5, converted.temperature)
        assertEquals(0.2, converted.topP)
    }
}
