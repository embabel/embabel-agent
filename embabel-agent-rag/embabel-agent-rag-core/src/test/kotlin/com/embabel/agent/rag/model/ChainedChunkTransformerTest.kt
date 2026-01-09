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
package com.embabel.agent.rag.model

import com.embabel.agent.rag.ingestion.ChunkTransformationContext
import com.embabel.agent.rag.ingestion.ChunkTransformer
import com.embabel.agent.rag.ingestion.transform.ChainedChunkTransformer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChainedChunkTransformerTest {

    private val testSection = LeafSection(
        id = "section-1",
        title = "Test Section",
        text = "Section content"
    )

    private val testContext = ChunkTransformationContext(
        section = testSection,
        document = null
    )

    @Test
    fun `transform with empty list returns original chunk unchanged`() {
        val chunk = Chunk.create(
            text = "original text",
            parentId = "parent-1",
            metadata = mapOf("key" to "value")
        )
        val transformer = ChainedChunkTransformer(emptyList())

        val result = transformer.transform(chunk, testContext)

        assertEquals(chunk.id, result.id)
        assertEquals(chunk.text, result.text)
        assertEquals(chunk.metadata, result.metadata)
    }

    @Test
    fun `transform with single transformer applies that transformer`() {
        val chunk = Chunk.create(
            text = "hello",
            parentId = "parent-1"
        )
        val uppercaseTransformer = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withText(chunk.text.uppercase())
        }
        val transformer = ChainedChunkTransformer(listOf(uppercaseTransformer))

        val result = transformer.transform(chunk, testContext)

        assertEquals("HELLO", result.text)
    }

    @Test
    fun `transform applies multiple transformers in sequence`() {
        val chunk = Chunk.create(
            text = "hello",
            parentId = "parent-1"
        )
        val appendWorld = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withText(chunk.text + " world")
        }
        val uppercaseTransformer = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withText(chunk.text.uppercase())
        }
        val transformer = ChainedChunkTransformer(listOf(appendWorld, uppercaseTransformer))

        val result = transformer.transform(chunk, testContext)

        assertEquals("HELLO WORLD", result.text)
    }

    @Test
    fun `transform applies transformers in correct order`() {
        val chunk = Chunk.create(
            text = "start",
            parentId = "parent-1"
        )
        val appendA = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withText(chunk.text + "-A")
        }
        val appendB = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withText(chunk.text + "-B")
        }
        val appendC = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withText(chunk.text + "-C")
        }
        val transformer = ChainedChunkTransformer(listOf(appendA, appendB, appendC))

        val result = transformer.transform(chunk, testContext)

        assertEquals("start-A-B-C", result.text)
    }

    @Test
    fun `transform accumulates metadata from each transformer`() {
        val chunk = Chunk.create(
            text = "text",
            parentId = "parent-1",
            metadata = mapOf("original" to true)
        )
        val addMetaA = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withAdditionalMetadata(chunk.metadata + mapOf("transformer_a" to true))
        }
        val addMetaB = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withAdditionalMetadata(chunk.metadata + mapOf("transformer_b" to true))
        }
        val transformer = ChainedChunkTransformer(listOf(addMetaA, addMetaB))

        val result = transformer.transform(chunk, testContext)

        assertEquals(true, result.metadata["original"])
        assertEquals(true, result.metadata["transformer_a"])
        assertEquals(true, result.metadata["transformer_b"])
    }

    @Test
    fun `transform preserves chunk id through all transformations`() {
        val chunk = Chunk.create(
            text = "text",
            parentId = "parent-1",
            id = "fixed-id"
        )
        val transformer1 = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withText("changed1")
        }
        val transformer2 = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withText("changed2")
        }
        val transformer = ChainedChunkTransformer(listOf(transformer1, transformer2))

        val result = transformer.transform(chunk, testContext)

        assertEquals("fixed-id", result.id)
    }

    @Test
    fun `transform preserves parentId through all transformations`() {
        val chunk = Chunk.create(
            text = "text",
            parentId = "my-parent"
        )
        val transformer1 = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withText("changed")
        }
        val transformer = ChainedChunkTransformer(listOf(transformer1))

        val result = transformer.transform(chunk, testContext)

        assertEquals("my-parent", result.parentId)
    }

    @Test
    fun `transformer can access text modified by previous transformer`() {
        val chunk = Chunk.create(
            text = "original",
            parentId = "parent-1"
        )
        val observedTexts = mutableListOf<String>()
        val recordingTransformer1 = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk {
                observedTexts.add(chunk.text)
                return chunk.withText("first")
            }
        }
        val recordingTransformer2 = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk {
                observedTexts.add(chunk.text)
                return chunk.withText("second")
            }
        }
        val transformer = ChainedChunkTransformer(listOf(recordingTransformer1, recordingTransformer2))

        transformer.transform(chunk, testContext)

        assertEquals(listOf("original", "first"), observedTexts)
    }

    @Test
    fun `transformer can access metadata modified by previous transformer`() {
        val chunk = Chunk.create(
            text = "text",
            parentId = "parent-1",
            metadata = mapOf("count" to 0)
        )
        val incrementCount = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk {
                val currentCount = chunk.metadata["count"] as Int
                return chunk.withAdditionalMetadata(chunk.metadata + mapOf("count" to currentCount + 1))
            }
        }
        val transformer = ChainedChunkTransformer(listOf(incrementCount, incrementCount, incrementCount))

        val result = transformer.transform(chunk, testContext)

        assertEquals(3, result.metadata["count"])
    }

    @Test
    fun `transformer receives context with section and document`() {
        val chunk = Chunk.create(
            text = "text",
            parentId = "parent-1"
        )
        var receivedContext: ChunkTransformationContext? = null
        val contextCapturingTransformer = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk {
                receivedContext = context
                return chunk
            }
        }
        val transformer = ChainedChunkTransformer(listOf(contextCapturingTransformer))

        transformer.transform(chunk, testContext)

        assertNotNull(receivedContext)
        assertEquals(testSection, receivedContext!!.section)
        assertNull(receivedContext!!.document)
    }

    @Test
    fun `transformer can use section from context`() {
        val chunk = Chunk.create(
            text = "text",
            parentId = "parent-1"
        )
        val addSectionTitle = object : ChunkTransformer {
            override fun transform(chunk: Chunk, context: ChunkTransformationContext): Chunk =
                chunk.withAdditionalMetadata(chunk.metadata + mapOf("section_title" to context.section.title))
        }
        val transformer = ChainedChunkTransformer(listOf(addSectionTitle))

        val result = transformer.transform(chunk, testContext)

        assertEquals("Test Section", result.metadata["section_title"])
    }
}
