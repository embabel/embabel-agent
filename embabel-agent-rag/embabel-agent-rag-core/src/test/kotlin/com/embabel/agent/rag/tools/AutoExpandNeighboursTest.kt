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
import com.embabel.common.core.types.SimilarityResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The contract of [SearchDefaults.expandNeighbours]: OFF unless asked for, and additive when on.
 */
class AutoExpandNeighboursTest {

    private fun chunk(id: String) = Chunk(id = id, text = "text of $id", parentId = "leaf-1", metadata = emptyMap())

    private fun hit(id: String, score: Double) = object : SimilarityResult<Chunk> {
        override val match: Chunk = chunk(id)
        override val score: Double = score
    }

    /** Returns two neighbours per request, plus one that duplicates an existing hit. */
    private val expander = object : ResultExpander {
        override fun expandResult(id: String, method: ResultExpander.Method, count: Int): List<ContentElement> =
            listOf(chunk("$id-before"), chunk("$id-after"), chunk("hit-2"))
    }

    @Test
    fun `expansion is off by default, so existing behaviour is unchanged`() {
        val hits = listOf(hit("hit-1", 0.9))
        assertEquals(hits, hits.withNeighbours(expander, SearchDefaults.DEFAULT.expandNeighbours))
        assertEquals(0, SearchDefaults.DEFAULT.expandNeighbours, "default must stay 0")
    }

    @Test
    fun `a store that cannot expand is simply not expanded`() {
        val hits = listOf(hit("hit-1", 0.9))
        assertEquals(hits, hits.withNeighbours(null, 4))
    }

    @Test
    fun `expansion only ever adds, keeping hits first and in order`() {
        val hits = listOf(hit("hit-1", 0.9), hit("hit-2", 0.5))
        val expanded = hits.withNeighbours(expander, 2)

        assertEquals(listOf("hit-1", "hit-2"), expanded.take(2).map { it.match.id },
            "hits must stay first and in order — callers read these positionally")
        assertTrue(expanded.size > hits.size, "expansion should add neighbours")
        assertEquals(expanded.map { it.match.id }.distinct(), expanded.map { it.match.id },
            "no duplicates: a neighbour that is already a hit must not appear twice")
        assertTrue(expanded.none { it.match.id == "hit-2" && it.score != 0.5 },
            "an existing hit keeps its own score, not a neighbour's")
    }

    @Test
    fun `a neighbour inherits the score of the hit that found it`() {
        val expanded = listOf(hit("hit-1", 0.77)).withNeighbours(expander, 2)
        val neighbour = expanded.first { it.match.id == "hit-1-after" }
        assertEquals(0.77, neighbour.score,
            "context must not be scored independently, or it could outrank the match that found it")
    }
}
