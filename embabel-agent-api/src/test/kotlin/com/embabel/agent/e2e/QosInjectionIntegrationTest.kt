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

import com.embabel.agent.api.annotation.support.DefaultActionMethodManager
import com.embabel.agent.api.annotation.support.DefaultMethodActionQosProvider
import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.AiBuilder
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue


@SpringBootTest
@ActiveProfiles("test")
@Import(
    value = [
        FakeConfig::class,
    ]
)
@TestPropertySource(properties = [
    "embabel.agent.platform.action-qos.default.max-attempts=6",
    "embabel.agent.platform.action-qos.agents.agent.method.max-attempts=6",
])
class QosInjectionIntegrationTest private constructor(
    @param:Autowired
    private val methodManager: DefaultActionMethodManager,
) {

    @Test
    fun `action qos correctly injected`() {
        assertTrue(methodManager.methodActionQosProvider is DefaultMethodActionQosProvider)
        assertTrue(
            (methodManager.methodActionQosProvider as DefaultMethodActionQosProvider).perActionQosProperties.agents
                .contains("agent")
        )
        assertEquals(
            (methodManager.methodActionQosProvider as DefaultMethodActionQosProvider).perActionQosProperties.default
                .maxAttempts, 6
        )
    }
}
