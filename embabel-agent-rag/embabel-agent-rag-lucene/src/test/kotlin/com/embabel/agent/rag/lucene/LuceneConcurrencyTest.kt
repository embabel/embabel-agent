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
package com.embabel.agent.rag.lucene

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

/**
 * Tests for concurrency behavior in LuceneSearchOperations.
 */
class LuceneConcurrencyTest {

    private lateinit var ragService: LuceneSearchOperations

    @BeforeEach
    fun setUp() {
        ragService = createTestRagService()
    }

    @AfterEach
    fun tearDown() {
        ragService.close()
    }

    @Test
    fun `should handle concurrent chunk storage operations`() {
        val numThreads = 10
        val documentsPerThread = 50

        val threads = (1..numThreads).map { threadIndex ->
            Thread {
                val documents = (1..documentsPerThread).map { docIndex ->
                    Document(
                        "thread-${threadIndex}-doc-${docIndex}",
                        "Content for thread $threadIndex document $docIndex",
                        emptyMap<String, Any>()
                    )
                }
                ragService.acceptDocuments(documents)
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val allChunks = ragService.findAll()
        assertEquals(numThreads * documentsPerThread, allChunks.size)

        val chunkIds = allChunks.map { it.id }.toSet()
        assertEquals(numThreads * documentsPerThread, chunkIds.size)
    }

    @Test
    fun `should handle concurrent read and write operations`() {
        val initialDocs = (1..100).map {
            Document("init-$it", "Initial doc $it", emptyMap<String, Any>())
        }
        ragService.acceptDocuments(initialDocs)

        val writerThread = Thread {
            repeat(50) { i ->
                ragService.acceptDocuments(
                    listOf(
                        Document("writer-$i", "Writer doc $i", emptyMap<String, Any>())
                    )
                )
            }
        }

        val readerThread = Thread {
            repeat(100) {
                ragService.findAll()
                ragService.findAllChunksById(listOf("init-1", "init-50", "writer-1"))
            }
        }

        writerThread.start()
        readerThread.start()

        writerThread.join()
        readerThread.join()

        val finalChunks = ragService.findAll()
        assertTrue(finalChunks.size >= 100)
    }
}
