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
package com.embabel.agent.a2a

import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class A2AContextKeyTest {

    @Test
    fun `stored value is readable from the same key instance`() {
        val ctx = Context.current().with(A2A_CONTEXT_ID_KEY, "ctx-123")
        assertEquals("ctx-123", ctx.get(A2A_CONTEXT_ID_KEY))
    }

    @Test
    fun `absent when context carries no value`() {
        assertNull(Context.current().get(A2A_CONTEXT_ID_KEY))
    }

    @Test
    fun `makeCurrent makes value visible on the thread`() {
        val scope = Context.current().with(A2A_CONTEXT_ID_KEY, "ctx-456").makeCurrent()
        scope.use {
            assertEquals("ctx-456", Context.current().get(A2A_CONTEXT_ID_KEY))
        }
        // after scope closes, original context is restored
        assertNull(Context.current().get(A2A_CONTEXT_ID_KEY))
    }

    @Test
    fun `two independently-named keys are distinct slots`() {
        // Documents the identity-equality behaviour that makes singleton allocation mandatory.
        val keyA = ContextKey.named<String>("a2a.context_id")
        val keyB = ContextKey.named<String>("a2a.context_id")
        val ctx = Context.current().with(keyA, "value-a")
        // keyB cannot read what was stored under keyA despite having the same name
        assertNull(ctx.get(keyB), "Different key instances are independent even with the same name")
    }
}
