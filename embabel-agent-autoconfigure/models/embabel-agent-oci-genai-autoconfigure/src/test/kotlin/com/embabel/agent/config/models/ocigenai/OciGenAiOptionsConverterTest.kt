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
package com.embabel.agent.config.models.ocigenai

import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OciGenAiOptionsConverterTest : OptionsConverterTestSupport<OciGenAiChatOptions>(
    optionsConverter = OciGenAiOptionsConverter,
) {

    @Test
    fun `should set standard chat options`() {
        val options = OciGenAiOptionsConverter.convertOptions(
            LlmOptions()
                .withTemperature(0.2)
                .withTopP(0.9)
                .withTopK(50)
                .withMaxTokens(123)
        )

        assertEquals(0.2, options.temperature)
        assertEquals(0.9, options.topP)
        assertEquals(50, options.topK)
        assertEquals(123, options.maxTokens)
    }

    @Test
    fun `should not override provider defaults`() {
        val options = OciGenAiOptionsConverter.convertOptions(LlmOptions())

        assertNull(options.model)
        assertNull(options.getCompartmentId())
        assertFalse(options.internalToolExecutionEnabled ?: true)
    }
}
