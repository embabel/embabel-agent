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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.core.Usage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.api.AnthropicApi

class UsageExtensionsTest {

    @Test
    fun `anthropicCacheCreationTokens should return null for non-Anthropic usage`() {
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = null)
        assertNull(usage.anthropicCacheCreationTokens())
    }

    @Test
    fun `anthropicCacheCreationTokens should return null when no cache created`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 0, 0)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertEquals(0, usage.anthropicCacheCreationTokens())
    }

    @Test
    fun `anthropicCacheCreationTokens should return tokens when cache created`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 1500, 0)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertEquals(1500, usage.anthropicCacheCreationTokens())
    }

    @Test
    fun `anthropicCacheReadTokens should return null for non-Anthropic usage`() {
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = null)
        assertNull(usage.anthropicCacheReadTokens())
    }

    @Test
    fun `anthropicCacheReadTokens should return null when no cache read`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 0, 0)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertEquals(0, usage.anthropicCacheReadTokens())
    }

    @Test
    fun `anthropicCacheReadTokens should return tokens when cache read`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 0, 2000)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertEquals(2000, usage.anthropicCacheReadTokens())
    }

    @Test
    fun `hasAnthropicCacheCreation should return false for non-Anthropic usage`() {
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = null)
        assertFalse(usage.hasAnthropicCacheCreation())
    }

    @Test
    fun `hasAnthropicCacheCreation should return false when no cache created`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 0, 0)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertFalse(usage.hasAnthropicCacheCreation())
    }

    @Test
    fun `hasAnthropicCacheCreation should return true when cache created`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 1500, 0)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertTrue(usage.hasAnthropicCacheCreation())
    }

    @Test
    fun `hasAnthropicCacheRead should return false for non-Anthropic usage`() {
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = null)
        assertFalse(usage.hasAnthropicCacheRead())
    }

    @Test
    fun `hasAnthropicCacheRead should return false when no cache read`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 0, 0)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertFalse(usage.hasAnthropicCacheRead())
    }

    @Test
    fun `hasAnthropicCacheRead should return true when cache read`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 0, 2000)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertTrue(usage.hasAnthropicCacheRead())
    }

    @Test
    fun `anthropicCacheSummary should return none for non-Anthropic usage`() {
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = null)
        assertEquals("cache[none]", usage.anthropicCacheSummary())
    }

    @Test
    fun `anthropicCacheSummary should return zeros when no cache activity`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 0, 0)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertEquals("cache[creation=0, read=0]", usage.anthropicCacheSummary())
    }

    @Test
    fun `anthropicCacheSummary should show creation when cache created`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 1500, 0)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertEquals("cache[creation=1500, read=0]", usage.anthropicCacheSummary())
    }

    @Test
    fun `anthropicCacheSummary should show read when cache read`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 0, 2000)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertEquals("cache[creation=0, read=2000]", usage.anthropicCacheSummary())
    }

    @Test
    fun `anthropicCacheSummary should show both creation and read`() {
        val anthropicUsage = AnthropicApi.Usage(100, 50, 1500, 2000)
        val usage = Usage(promptTokens = 100, completionTokens = 50, nativeUsage = anthropicUsage)
        assertEquals("cache[creation=1500, read=2000]", usage.anthropicCacheSummary())
    }
}
