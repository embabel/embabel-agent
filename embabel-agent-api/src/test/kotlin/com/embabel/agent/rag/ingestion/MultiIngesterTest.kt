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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.WritableStore
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TextSplitter
import java.util.*

class MultiIngesterTest {

    private lateinit var mockWritableStore1: WritableStore
    private lateinit var mockWritableStore2: WritableStore
    private lateinit var mockTextSplitter: TextSplitter

    @BeforeEach
    fun setup() {
        mockWritableStore1 = mockk()
        mockWritableStore2 = mockk()
        mockTextSplitter = mockk()

        every { mockWritableStore1.name } returns "rag-service-1"
        every { mockWritableStore2.name } returns "rag-service-2"
        every { mockWritableStore1.write(any()) } just Runs
        every { mockWritableStore2.write(any()) } just Runs
    }

    @Disabled("Implement tests")
    @Nested
    inner class WriteContentTests {
        // Placeholder for future tests of writeContent if needed
    }


    @Nested
    inner class ConstructorAndInitializationTests {

        @Test
        fun `test constructor with empty rag services list`() {
            val multiIngester = MultiIngester(emptyList())

            assertFalse(multiIngester.active(), "Should not be active with empty rag services")
            assertTrue(multiIngester.stores.isEmpty(), "Should have empty rag services list")
        }

        @Test
        fun `test constructor with single rag service`() {
            val multiIngester = MultiIngester(listOf(mockWritableStore1))

            assertTrue(multiIngester.active(), "Should be active with one rag service")
            assertEquals(1, multiIngester.stores.size, "Should have one rag service")
            assertEquals(mockWritableStore1, multiIngester.stores[0], "Should contain the correct rag service")
        }

        @Test
        fun `test constructor with multiple rag services`() {
            val multiIngester = MultiIngester(listOf(mockWritableStore1, mockWritableStore2))

            assertTrue(multiIngester.active(), "Should be active with multiple rag services")
            assertEquals(2, multiIngester.stores.size, "Should have two rag services")
            assertTrue(multiIngester.stores.contains(mockWritableStore1), "Should contain first rag service")
            assertTrue(multiIngester.stores.contains(mockWritableStore2), "Should contain second rag service")
        }

        @Test
        fun `test constructor with custom text splitter`() {
            val multiIngester = MultiIngester(listOf(mockWritableStore1), mockTextSplitter)

            assertEquals(mockTextSplitter, multiIngester.splitter, "Should use custom text splitter")
        }

        @Test
        fun `test constructor with default text splitter`() {
            val multiIngester = MultiIngester(listOf(mockWritableStore1))

            assertNotNull(multiIngester.splitter, "Should have a default text splitter")
            // Default is TokenTextSplitter - we can't easily test the exact type without reflection
        }
    }

    @Nested
    inner class ActiveStatusTests {

        @Test
        fun `test active returns false with empty rag services`() {
            val multiIngester = MultiIngester(emptyList())

            assertFalse(multiIngester.active(), "Should not be active with no rag services")
        }

        @Test
        fun `test active returns true with rag services`() {
            val multiIngester = MultiIngester(listOf(mockWritableStore1))

            assertTrue(multiIngester.active(), "Should be active with rag services")
        }

        @Test
        fun `test active returns true with multiple rag services`() {
            val multiIngester = MultiIngester(listOf(mockWritableStore1, mockWritableStore2))

            assertTrue(multiIngester.active(), "Should be active with multiple rag services")
        }
    }

    @Nested
    inner class DocumentProcessingTests {

        @Test
        fun `test accept with documents writes to all services`() {
            // Create test documents directly (no files)
            val doc1 = Document("This is a test document", mapOf("id" to "chunk-1"))
            val doc2 = Document("for ingestion.", mapOf("id" to "chunk-2"))
            val documents = listOf(doc1, doc2)

            val multiIngester = MultiIngester(listOf(mockWritableStore1, mockWritableStore2))

            val result = multiIngester.accept(documents)

            // Verify services were called with the documents
            verify { mockWritableStore1.write(documents) }
            verify { mockWritableStore2.write(documents) }
        }

        @Test
        fun `test accept with no rag services processes but doesn't write`() {
            val documents = listOf(Document("Test content", mapOf("id" to "test-1")))
            val multiIngester = MultiIngester(emptyList())

            // Should not throw exception
            assertDoesNotThrow {
                multiIngester.accept(documents)
            }
        }

        @Test
        fun `test accept with large document list processes all`() {
            // Create multiple documents to simulate splitting
            val documents = (1..5).map {
                Document("Chunk $it content", mapOf("id" to "chunk-$it"))
            }

            val multiIngester = MultiIngester(listOf(mockWritableStore1))

            multiIngester.accept(documents)

            verify { mockWritableStore1.write(documents) }
        }

        @Test
        fun `test accept with empty document list`() {
            val multiIngester = MultiIngester(listOf(mockWritableStore1))

            multiIngester.accept(emptyList())

            verify { mockWritableStore1.write(emptyList()) }
        }

        @Test
        fun `test accept with single document`() {
            val document = Document("Single document", mapOf("id" to "single"))
            val multiIngester = MultiIngester(listOf(mockWritableStore1))

            multiIngester.accept(listOf(document))

            verify { mockWritableStore1.write(listOf(document)) }
        }

        @Test
        fun `test accept with different document types and metadata`() {
            val documents = listOf(
                Document("Text content", mapOf("type" to "txt", "id" to "text-1")),
                Document("# Markdown content", mapOf("type" to "md", "id" to "md-1")),
                Document("public class Test {}", mapOf("type" to "java", "id" to "java-1")),
                Document("{\"key\": \"value\"}", mapOf("type" to "json", "id" to "json-1"))
            )

            val multiIngester = MultiIngester(listOf(mockWritableStore1, mockWritableStore2))

            multiIngester.accept(documents)

            verify { mockWritableStore1.write(documents) }
            verify { mockWritableStore2.write(documents) }
        }
    }


    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `test accept throws exception when rag service fails`() {
            val document = Document("Test content", mapOf("id" to "test-1"))
            every { mockWritableStore1.write(any()) } throws RuntimeException("Write failed")

            val multiIngester = MultiIngester(listOf(mockWritableStore1))

            assertThrows(RuntimeException::class.java) {
                multiIngester.accept(listOf(document))
            }

            verify { mockWritableStore1.write(listOf(document)) }
        }

        @Test
        fun `test accept with multiple services fails fast on first exception`() {
            val documents = listOf(Document("Test content", mapOf("id" to "test-1")))

            // Make first service throw exception
            every { mockWritableStore1.write(any()) } throws RuntimeException("Service 1 failed")
            every { mockWritableStore2.write(any()) } just Runs

            val multiIngester = MultiIngester(listOf(mockWritableStore1, mockWritableStore2))

            assertThrows(RuntimeException::class.java) {
                multiIngester.accept(documents)
            }

            verify { mockWritableStore1.write(documents) }
            // Second service should not be called due to exception in first
            verify(exactly = 0) { mockWritableStore2.write(any()) }
        }
    }

    @Nested
    inner class InfoStringTests {

        @Test
        fun `test infoString with no rag services`() {
            val multiIngester = MultiIngester(emptyList())

            val infoString = multiIngester.infoString(verbose = false, indent = 0)

            assertEquals("No RAG services", infoString, "Should indicate no RAG services")
        }

    }

    @Nested
    inner class IntegrationTests {

        @Test
        fun `test complete workflow with realistic document scenario`() {
            // Create realistic document chunks as they would come from a splitter
            val chunks = listOf(
                Document(
                    "# Introduction\nThis is a comprehensive document about testing.",
                    mapOf("id" to UUID.randomUUID().toString(), "type" to "header")
                ),
                Document(
                    "## Section 1\nContent for section 1 with important information.",
                    mapOf("id" to UUID.randomUUID().toString(), "type" to "section")
                ),
                Document(
                    "## Section 2\nMore content with different topics and details.",
                    mapOf("id" to UUID.randomUUID().toString(), "type" to "section")
                ),
                Document(
                    "## Conclusion\nFinal thoughts and summary of the document.",
                    mapOf("id" to UUID.randomUUID().toString(), "type" to "conclusion")
                )
            )

            val multiIngester = MultiIngester(listOf(mockWritableStore1, mockWritableStore2))

            multiIngester.accept(chunks)

            // Verify both services received all chunks
            verify { mockWritableStore1.write(chunks) }
            verify { mockWritableStore2.write(chunks) }
        }

        @Test
        fun `test workflow with mixed service types and configurations`() {
            // Create additional mock services with different characteristics
            val mockVectorService = mockk<WritableStore>()
            val mockGraphService = mockk<WritableStore>()

            every { mockVectorService.name } returns "vector-store"
            every { mockGraphService.name } returns "knowledge-graph"
            every { mockVectorService.write(any()) } just Runs
            every { mockGraphService.write(any()) } just Runs

            val chunk = Document("Content for mixed service ingestion", mapOf("id" to "mixed-1"))
            val documents = listOf(chunk)

            val multiIngester = MultiIngester(
                listOf(mockWritableStore1, mockVectorService, mockGraphService)
            )

            multiIngester.accept(documents)

            // Verify all services were called
            verify { mockWritableStore1.write(documents) }
            verify { mockVectorService.write(documents) }
            verify { mockGraphService.write(documents) }
        }

        @Test
        fun `test performance characteristics with large number of services`() {
            // Create multiple mock services
            val services = (1..10).map { index ->
                mockk<WritableStore>().also { service ->
                    every { service.name } returns "service-$index"
                    every { service.write(any()) } just Runs
                }
            }

            val document = Document("Performance test content", mapOf("id" to "perf-1"))
            val documents = listOf(document)

            val multiIngester = MultiIngester(services)

            val startTime = System.currentTimeMillis()
            multiIngester.accept(documents)
            val endTime = System.currentTimeMillis()

            // Verify reasonable performance (should complete quickly with mocks)
            val duration = endTime - startTime
            assertTrue(duration < 1000, "Should complete within 1 second for 10 mock services")

            // Verify all services were called
            services.forEach { service ->
                verify { service.write(documents) }
            }
        }
    }
}
