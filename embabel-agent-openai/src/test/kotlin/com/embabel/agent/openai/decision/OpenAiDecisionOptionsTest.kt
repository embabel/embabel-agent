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
package com.embabel.agent.openai.decision

import com.embabel.common.ai.decision.DecisionCall
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.Questions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiDecisionOptionsTest {

    private val request = DecisionRequest.of(
        "hello",
        DecisionSpec.of(Questions.named("urgent").proposition("Is this urgent?").build()),
    )

    @Test
    fun `key ids are namespaced under openai`() {
        assertEquals("openai.prompt-cache-key", OpenAiDecisionOptions.PROMPT_CACHE_KEY.id)
        assertEquals("openai.safety-identifier", OpenAiDecisionOptions.SAFETY_IDENTIFIER.id)
        assertEquals(String::class.java, OpenAiDecisionOptions.PROMPT_CACHE_KEY.type)
        assertEquals(String::class.java, OpenAiDecisionOptions.SAFETY_IDENTIFIER.type)
    }

    @Test
    fun `block sets both keys`() {
        val call = DecisionCall(request) {
            openAi {
                promptCacheKey = "triage-v1"
                safetyIdentifier = "user-42"
            }
        }
        assertEquals("triage-v1", call.option(OpenAiDecisionOptions.PROMPT_CACHE_KEY))
        assertEquals("user-42", call.option(OpenAiDecisionOptions.SAFETY_IDENTIFIER))
    }

    @Test
    fun `block that assigns one property sets only that key`() {
        val call = DecisionCall(request) {
            openAi { safetyIdentifier = "user-42" }
        }
        assertEquals(setOf(OpenAiDecisionOptions.SAFETY_IDENTIFIER), call.optionKeys)
        assertNull(call.option(OpenAiDecisionOptions.PROMPT_CACHE_KEY))
    }

    @Test
    fun `property assigned null sets no key`() {
        val call = DecisionCall(request) {
            openAi {
                promptCacheKey = "triage-v1"
                safetyIdentifier = null
            }
        }
        assertEquals(setOf(OpenAiDecisionOptions.PROMPT_CACHE_KEY), call.optionKeys)
    }

    @Test
    fun `empty block sets no keys`() {
        val call = DecisionCall(request) { openAi { } }
        assertTrue(call.optionKeys.isEmpty())
        assertEquals(DecisionCall.of(request), call)
    }

    @Test
    fun `block result equals the Java style chain`() {
        val fromBlock = DecisionCall(request) {
            openAi {
                promptCacheKey = "triage-v1"
                safetyIdentifier = "user-42"
            }
        }
        val fromChain = DecisionCall.of(request)
            .withOption(OpenAiDecisionOptions.PROMPT_CACHE_KEY, "triage-v1")
            .withOption(OpenAiDecisionOptions.SAFETY_IDENTIFIER, "user-42")
        assertEquals(fromChain, fromBlock)
    }
}
