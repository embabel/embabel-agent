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
package com.embabel.agent.config.models

import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class Gpt5ChatOptionsConverterTest(
) {

    @Test
    fun `ignores temperature`() {
        val llmo = LlmOptions(temperature = 0.5)
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo)
        assertEquals(null, options.temperature, "Custom temperature should be ignored for GPT-5")
    }

    @Test
    fun `respects non-temperature options`() {
        val llmo = LlmOptions.fromModel(OpenAiModels.GPT_5).withTopK(10).withTopP(.2)
        val options = Gpt5ChatOptionsConverter.convertOptions(llmo)
        assertEquals(llmo.topP, options.topP, "Top P should be preserved for GPT-5")
        assertNull(options.temperature, "Temperature should not be set for GPT-5")
    }

    @Disabled("We not support thinking effort yet")
    @Test
    fun `supports thinking effort`() {

    }

}
