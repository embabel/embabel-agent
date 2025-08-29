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
package com.embabel.agent.e2e

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.AiBuilder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertNotNull

@Component
class TakesAi(
    val ai: Ai,
) {

    fun myMethod() {
        assertNotNull(ai.withAutoLlm())
    }

}

@Component
class TakesAiFactory(
    private val aiBuilder: AiBuilder,
) {

    fun myMethod() {
        assertNotNull(aiBuilder.ai().withAutoLlm())
    }

}

@SpringBootTest
@ActiveProfiles("test")
@Import(
    value = [
        FakeConfig::class,
        TakesAi::class,
    ]
)
class AiInjectionIntegrationTest(
    @param:Autowired
    private val takesAi: TakesAi,
    @param:Autowired
    private val takesAiFactory: TakesAiFactory,
) {

    @Test
    fun `test can inject Ai into action`() {
        assertNotNull(takesAi.ai, "Ai should be injected")
        assertNotNull(takesAi.myMethod())
        val text = takesAi.ai.withAutoLlm().generateText("some text")
        assertNotNull(text)
    }

    @Test
    fun `test can inject AiFactory into action`() {
        assertNotNull(takesAiFactory.myMethod(), "Ai should be injected")
    }
}
