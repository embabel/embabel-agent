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
package com.embabel.agent.anthropic

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Usage as AnthropicSdkUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnthropicNativeUsageReaderTest {

    @Test
    fun `short-TTL writes never go negative when the 1h count exceeds the reported total`() {
        val sdkUsage = jsonMapper().readValue(
            """{"input_tokens":10,"output_tokens":5,"cache_creation_input_tokens":100,""" +
                """"cache_creation":{"ephemeral_1h_input_tokens":300}}""",
            AnthropicSdkUsage::class.java,
        )
        val native = sdkUsage.toNativeUsage()
        assertEquals(0, native.cacheWriteInputTokens)
        assertEquals(300, native.cacheWriteLongInputTokens)
    }
}
