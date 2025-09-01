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

import com.embabel.agent.api.common.ExecutingOperationContext
import com.embabel.agent.core.AgentPlatform
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertNotNull

@Component
class TakesOperationContext(
    private val eop: ExecutingOperationContext,
) {

    fun myMethod() {
        assertNotNull(eop.ai().withAutoLlm())
    }

}

@SpringBootTest
@ActiveProfiles("test")
@Import(
    value = [
        FakeConfig::class,
        TakesOperationContext::class,
    ]
)
class OperationContextInjectionIntegrationTest(
    @param:Autowired
    private val agentPlatform: AgentPlatform,
) {

    @Test
    fun `test can inject operation context into action`() {

    }
}
