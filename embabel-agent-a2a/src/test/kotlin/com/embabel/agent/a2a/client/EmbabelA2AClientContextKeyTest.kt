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
package com.embabel.agent.a2a.client

import com.embabel.agent.a2a.A2A_CONTEXT_ID_KEY
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.opentelemetry.context.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class EmbabelA2AClientContextKeyTest {

    @Mock
    private lateinit var httpClientFactory: A2AHttpClientFactory

    private val client by lazy {
        EmbabelA2AClient(httpClientFactory, EmbabelObjectMapperHolder.createDefault())
    }

    @Test
    fun `resolveContextId prefers OTel context key over message contextId`() {
        val scope = Context.current().with(A2A_CONTEXT_ID_KEY, "upstream-ctx").makeCurrent()
        scope.use {
            assertEquals(
                "upstream-ctx",
                client.resolveContextId("msg-ctx-should-be-ignored"),
                "OTel context key must take priority over message contextId",
            )
        }
    }

    @Test
    fun `resolveContextId falls back to message contextId when context key is absent`() {
        // No A2A_CONTEXT_ID_KEY set — bare context
        assertEquals(
            "msg-ctx-fallback",
            client.resolveContextId("msg-ctx-fallback"),
            "Should fall back to message contextId when context key is absent",
        )
    }

    @Test
    fun `resolveContextId returns null when context key absent and message contextId null`() {
        assertNull(client.resolveContextId(null))
    }
}
