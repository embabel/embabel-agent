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
package com.embabel.agent.rag.tools

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.service.ResultExpander
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResultExpanderToolsListenerTest {

    private fun chunk(id: String, text: String): Chunk =
        Chunk(id = id, text = text, parentId = "parent", metadata = emptyMap())

    private class FakeExpander(private val elements: List<ContentElement>) : ResultExpander {
        override fun expandResult(
            id: String,
            method: ResultExpander.Method,
            elementsToAdd: Int,
        ): List<ContentElement> = elements
    }

    @Test
    fun `broadenChunk reports expanded chunks to the listener at full score`() {
        // An observer (attribution, quote verification) must see what the model sees:
        // content surfaced by expansion is evidence exactly like a search hit.
        var events = mutableListOf<ResultsEvent>()
        val tools = ResultExpanderTools(
            FakeExpander(listOf(chunk("c1", "Section 3.01. Certificate of Incorporation"))),
            resultsListener = { events.add(it) },
        )
        val out = tools.broadenChunk("seed")
        assertTrue(out.contains("3.01"), out)
        assertEquals(1, events.size)
        assertEquals(1, events[0].results.size)
        assertTrue(events[0].results.all { it.score == 1.0 })
        assertEquals("broadenChunk: seed", events[0].query)
    }

    @Test
    fun `no expansion means no event`() {
        var fired = false
        val tools = ResultExpanderTools(FakeExpander(emptyList()), resultsListener = { fired = true })
        tools.broadenChunk("seed")
        assertTrue(!fired)
    }
}
