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
package com.embabel.agent.config.models.openai.compatible

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for custom model parsing logic used in OpenAiModelsConfig.
 * This validates the parsing of the OPENAI_CUSTOM_MODELS environment variable.
 */
class CustomModelParsingTest {

    @Test
    fun `should parse single custom model`() {
        val customModels = "llama-3.3-70b-versatile"
        val result = parseCustomModels(customModels)

        assertEquals(1, result.size)
        assertEquals("llama-3.3-70b-versatile", result[0])
    }

    @Test
    fun `should parse multiple custom models`() {
        val customModels = "llama-3.3-70b-versatile,mixtral-8x7b-32768,gemma2-9b-it"
        val result = parseCustomModels(customModels)

        assertEquals(3, result.size)
        assertEquals("llama-3.3-70b-versatile", result[0])
        assertEquals("mixtral-8x7b-32768", result[1])
        assertEquals("gemma2-9b-it", result[2])
    }

    @Test
    fun `should trim whitespace from model names`() {
        val customModels = "  llama-3.3-70b-versatile  ,  mixtral-8x7b-32768  "
        val result = parseCustomModels(customModels)

        assertEquals(2, result.size)
        assertEquals("llama-3.3-70b-versatile", result[0])
        assertEquals("mixtral-8x7b-32768", result[1])
    }

    @Test
    fun `should filter out blank entries`() {
        val customModels = "llama-3.3-70b-versatile,,  ,mixtral-8x7b-32768"
        val result = parseCustomModels(customModels)

        assertEquals(2, result.size)
        assertEquals("llama-3.3-70b-versatile", result[0])
        assertEquals("mixtral-8x7b-32768", result[1])
    }

    @Test
    fun `should return empty list for null input`() {
        val result = parseCustomModels(null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `should return empty list for blank input`() {
        val result = parseCustomModels("   ")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `should return empty list for only commas`() {
        val result = parseCustomModels(",,,")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `should handle model names with special characters`() {
        val customModels = "openai/gpt-oss-120b,meta-llama/llama-3.3-70b"
        val result = parseCustomModels(customModels)

        assertEquals(2, result.size)
        assertEquals("openai/gpt-oss-120b", result[0])
        assertEquals("meta-llama/llama-3.3-70b", result[1])
    }

    /**
     * Replicates the parsing logic from OpenAiModelsConfig for testing.
     */
    private fun parseCustomModels(customModels: String?): List<String> {
        return customModels
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }
}
