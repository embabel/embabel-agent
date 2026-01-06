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
package com.embabel.agent.test.unit

import com.embabel.agent.api.common.PromptRunner
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OperationContextTest {

    @Test
    fun `ai uses correct LLM`() {
        val oc = FakeOperationContext()
        val llmo = LlmOptions.withModel("foobar")
        val pr: PromptRunner = oc.ai().withLlm(llmo)
        assertEquals(llmo, pr.llm)

    }

}
