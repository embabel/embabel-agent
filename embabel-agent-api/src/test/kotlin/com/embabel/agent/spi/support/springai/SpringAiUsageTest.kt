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
package com.embabel.agent.spi.support.springai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.metadata.DefaultUsage

class SpringAiUsageTest {

    @Test
    fun `converts Spring AI Usage to Embabel Usage`() {
        val springUsage = DefaultUsage(100, 50)

        val embabelUsage = springUsage.toEmbabelUsage()

        assertEquals(100, embabelUsage.promptTokens)
        assertEquals(50, embabelUsage.completionTokens)
    }

    @Test
    fun `preserves total tokens calculation`() {
        val springUsage = DefaultUsage(200, 100)

        val embabelUsage = springUsage.toEmbabelUsage()

        assertEquals(300, embabelUsage.totalTokens)
    }
}
