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
package com.embabel.agent.rag.service

import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The contract around [TextQueryMode]: what an implementation gets for free, and the one invariant
 * that cannot be left to convention.
 */
class TextQueryModeTest {

    private open class Stub(
        override val supportedQueryModes: Set<TextQueryMode> = setOf(TextQueryMode.LUCENE_EXPRESSION),
    ) : TextSearch {
        override fun supportsType(type: String) = true
        override fun <T : Retrievable> textSearch(
            request: TextSimilaritySearchRequest,
            clazz: Class<T>,
        ): List<SimilarityResult<T>> = emptyList()
    }

    @Test
    @DisplayName("an implementation that declares nothing behaves as it always has")
    fun `default is LUCENE_EXPRESSION`() {
        // Every implementation predating the mode passed the caller's query through untouched.
        // Defaulting anywhere else would silently change what those stores do with an operator.
        val store = object : Stub() {}
        assertEquals(setOf(TextQueryMode.LUCENE_EXPRESSION), store.supportedQueryModes)
        assertEquals(TextQueryMode.LUCENE_EXPRESSION, store.queryMode)
    }

    @Test
    @DisplayName("declaring one supported mode selects it without restating it")
    fun `single supported mode becomes the active mode`() {
        val store = object : Stub(setOf(TextQueryMode.LITERAL)) {}
        assertEquals(TextQueryMode.LITERAL, store.queryMode)
    }

    @Test
    @DisplayName("the active mode must be one the implementation can actually serve")
    fun `queryMode is a member of supportedQueryModes`() {
        // The invariant worth pinning. A store configured into a mode it cannot serve does not fail
        // loudly — it quietly stops honouring operators, or starts escaping input the caller
        // composed, and the tool description tells the model the opposite of what happens.
        val stores = listOf(
            object : Stub() {},
            object : Stub(setOf(TextQueryMode.LITERAL)) {},
            object : Stub(setOf(TextQueryMode.LITERAL, TextQueryMode.LUCENE_EXPRESSION)) {},
        )
        stores.forEach { store ->
            assertTrue(
                store.queryMode in store.supportedQueryModes,
                "active mode ${store.queryMode} not in supported ${store.supportedQueryModes}",
            )
        }
    }

    @Test
    @DisplayName("supporting both leaves the choice to the deployment")
    fun `an implementation may serve both modes`() {
        val store = object : Stub(setOf(TextQueryMode.LITERAL, TextQueryMode.LUCENE_EXPRESSION)) {
            override val queryMode = TextQueryMode.LITERAL
        }
        assertEquals(TextQueryMode.LITERAL, store.queryMode)
        assertTrue(TextQueryMode.LUCENE_EXPRESSION in store.supportedQueryModes, "still available to configure")
    }
}
