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
package com.embabel.agent.config.models.bedrock

import com.embabel.agent.test.models.OptionsConverterTestSupport
import com.embabel.common.ai.model.LlmOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.bedrock.converse.BedrockChatOptions

class BedrockOptionsConverterTest : OptionsConverterTestSupport(
    optionsConverter = BedrockOptionsConverter
) {

    @Test
    fun `should create provider-specific options`() {
        val options = optionsConverter.convertOptions(LlmOptions(), "test-model")

        assertThat(options).isInstanceOf(BedrockChatOptions::class.java)
    }
}
