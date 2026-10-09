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

import com.embabel.common.util.EmbabelObjectMapperHolder
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.baggage.BaggageEntryMetadata
import io.opentelemetry.context.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class EmbabelA2AClientBaggageTest {

    @Mock
    private lateinit var httpClientFactory: A2AHttpClientFactory

    private val client by lazy {
        EmbabelA2AClient(httpClientFactory, EmbabelObjectMapperHolder.createDefault())
    }

    @Test
    fun `resolveContextId prefers upstream baggage over message contextId`() {
        val scope = Baggage.empty().toBuilder()
            .put(EmbabelA2AClient.BAGGAGE_KEY, "upstream-ctx", BaggageEntryMetadata.empty())
            .build()
            .storeInContext(Context.current())
            .makeCurrent()

        scope.use {
            assertEquals(
                "upstream-ctx",
                client.resolveContextId("msg-ctx-should-be-ignored"),
                "Upstream baggage contextId must take priority over message contextId",
            )
        }
    }

    @Test
    fun `resolveContextId falls back to message contextId when baggage has no a2a key`() {
        val scope = Baggage.empty().toBuilder()
            .put("some.other.key", "other-value", BaggageEntryMetadata.empty())
            .build()
            .storeInContext(Context.current())
            .makeCurrent()

        scope.use {
            assertEquals(
                "msg-ctx-fallback",
                client.resolveContextId("msg-ctx-fallback"),
                "Should fall back to message contextId when baggage has no a2a.context_id",
            )
        }
    }

    @Test
    fun `resolveContextId returns null when baggage empty and message contextId null`() {
        val scope = Baggage.empty().toBuilder()
            .put("some.other.key", "other-value", BaggageEntryMetadata.empty())
            .build()
            .storeInContext(Context.current())
            .makeCurrent()

        scope.use {
            assertNull(client.resolveContextId(null))
        }
    }
}
