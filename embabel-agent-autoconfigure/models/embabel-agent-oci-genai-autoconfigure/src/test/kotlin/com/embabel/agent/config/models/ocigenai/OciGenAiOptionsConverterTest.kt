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
        assertNull(options.compartmentId)
        assertFalse(options.internalToolExecutionEnabled ?: true)
    }

    @Test
    fun `converted LlmOptions preserve provider-specific defaults when merged`() {
        val defaults = OciGenAiChatOptions.builder()
            .model("cohere.command-a-03-2025")
            .compartmentId("ocid1.compartment.oc1..test")
            .servingMode(OciGenAiServingMode.DEDICATED)
            .endpointId("ocid1.generativeaiendpoint.oc1..test")
            .apiFormat(OciGenAiApiFormat.COHERE_V2)
            .temperature(0.7)
            .build()
        val runtimeOptions = OciGenAiOptionsConverter.convertOptions(
            LlmOptions().withTemperature(0.2)
        )

        val merged = defaults.merge(runtimeOptions)

        assertEquals("cohere.command-a-03-2025", merged.model)
        assertEquals("ocid1.compartment.oc1..test", merged.compartmentId)
        assertEquals(OciGenAiServingMode.DEDICATED, merged.servingMode)
        assertEquals("ocid1.generativeaiendpoint.oc1..test", merged.endpointId)
        assertEquals(OciGenAiApiFormat.COHERE_V2, merged.apiFormat)
        assertEquals(0.2, merged.temperature)
    }

    @Test
    fun `explicit OCI runtime options override provider-specific defaults`() {
        val defaults = OciGenAiChatOptions.builder()
            .servingMode(OciGenAiServingMode.DEDICATED)
            .endpointId("ocid1.generativeaiendpoint.oc1..default")
            .apiFormat(OciGenAiApiFormat.COHERE_V2)
            .build()
        val runtimeOptions = OciGenAiChatOptions.builder()
            .servingMode(OciGenAiServingMode.ON_DEMAND)
            .apiFormat(OciGenAiApiFormat.GENERIC)
            .build()

        val merged = defaults.merge(runtimeOptions)

        assertEquals(OciGenAiServingMode.ON_DEMAND, merged.servingMode)
        assertEquals(OciGenAiApiFormat.GENERIC, merged.apiFormat)
    }

    @Test
    fun `copy preserves explicit serving mode and api format tracking`() {
        val defaults = OciGenAiChatOptions.builder()
            .servingMode(OciGenAiServingMode.DEDICATED)
            .endpointId("ocid1.generativeaiendpoint.oc1..default")
            .apiFormat(OciGenAiApiFormat.COHERE_V2)
            .build()

        val explicitCopy = defaults.copy<OciGenAiChatOptions>()
        val unconfiguredCopy = OciGenAiChatOptions.builder()
            .build()
            .copy<OciGenAiChatOptions>()

        val preservedDefaults = explicitCopy.merge(OciGenAiOptionsConverter.convertOptions(LlmOptions()))
        val preservedAfterUnconfiguredMerge = defaults.merge(unconfiguredCopy)

        assertEquals(OciGenAiServingMode.DEDICATED, preservedDefaults.servingMode)
        assertEquals(OciGenAiApiFormat.COHERE_V2, preservedDefaults.apiFormat)
        assertEquals(OciGenAiServingMode.DEDICATED, preservedAfterUnconfiguredMerge.servingMode)
        assertEquals(OciGenAiApiFormat.COHERE_V2, preservedAfterUnconfiguredMerge.apiFormat)
    }
}
