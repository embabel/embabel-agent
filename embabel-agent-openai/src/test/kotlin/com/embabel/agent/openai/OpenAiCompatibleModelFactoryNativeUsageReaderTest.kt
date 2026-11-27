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
package com.embabel.agent.openai

import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.support.springai.SpringAiNativeUsageReader
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.OptionsConverter
import com.embabel.common.ai.model.PricingModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.retry.support.RetryTemplate
import java.time.LocalDate

class OpenAiCompatibleModelFactoryNativeUsageReaderTest {

    private val factory = OpenAiCompatibleModelFactory(baseUrl = null, apiKey = "test-key")

    @Test
    fun `a provider can pass its own native usage reader, OpenAI's is the default`() {
        val reader = SpringAiNativeUsageReader { null }
        val custom = factory.openAiCompatibleLlm(
            model = "foo", pricingModel = PricingModel.ALL_YOU_CAN_EAT,
            provider = GoogleGenAiModels.PROVIDER, knowledgeCutoffDate = null, nativeUsageReader = reader,
        ) as SpringAiLlmService
        val default = factory.openAiCompatibleLlm(
            model = "foo", pricingModel = PricingModel.ALL_YOU_CAN_EAT, provider = "Test", knowledgeCutoffDate = null,
        ) as SpringAiLlmService
        assertSame(reader, custom.nativeUsageReader)
        assertSame(OpenAiNativeUsageReader, default.nativeUsageReader)
    }

    /** Callers compiled against 1.5.4 link to these exact JVM descriptors of `openAiCompatibleLlm`. */
    @Test
    fun `openAiCompatibleLlm keeps its 1_5_4 overloads and default bridge`() {
        val f = OpenAiCompatibleModelFactory::class.java
        val required = arrayOf(String::class.java, PricingModel::class.java, String::class.java, LocalDate::class.java)
        val all = required + arrayOf(OptionsConverter::class.java, RetryTemplate::class.java, java.lang.Boolean.TYPE)
        for (arity in required.size..all.size) {
            assertEquals(LlmService::class.java, f.getMethod("openAiCompatibleLlm", *all.copyOfRange(0, arity)).returnType)
        }
        val default = f.getMethod("openAiCompatibleLlm\$default", f, *all, Integer.TYPE, Any::class.java)
        assertEquals(LlmService::class.java, default.returnType)
        // factory.openAiCompatibleLlm("foo", pricing, "LM Studio", null): arguments 4, 5 and 6 omitted.
        val llm = default.invoke(
            null, factory, "foo", PricingModel.ALL_YOU_CAN_EAT, "LM Studio", null, null, null, true, 0b1110000, null,
        ) as SpringAiLlmService
        assertEquals("foo", llm.name)
        assertSame(OpenAiNativeUsageReader, llm.nativeUsageReader)
        // forwardsReasoningEffort was omitted, so LM Studio gets the default (false), not the passed true.
        val options = LlmOptions().withOpenAiReasoningEffort("none")
        assertNull((llm.optionsConverter.convertOptions(options, "foo") as OpenAiChatOptions).reasoningEffort)
    }
}
