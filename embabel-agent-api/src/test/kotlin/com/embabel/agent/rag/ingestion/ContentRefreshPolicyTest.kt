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

import com.embabel.agent.rag.model.MaterializedDocument
import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.store.ChunkingContentElementRepository
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ContentRefreshPolicyTest {

    private lateinit var mockRepository: ChunkingContentElementRepository
    private lateinit var mockReader: HierarchicalContentReader

    @BeforeEach
    fun setUp() {
        mockRepository = mockk<ChunkingContentElementRepository>()
        mockReader = mockk<HierarchicalContentReader>()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Nested
    inner class NeverRefreshExistingDocumentContentPolicyTest {

        @Test
        fun `shouldReread returns true when document does not exist`() {
            val uri = "test://new-document"
            every { mockRepository.existsRootWithUri(uri) } returns false

            val result = NeverRefreshExistingDocumentContentPolicy.shouldReread(mockRepository, uri)

            assertTrue(result)
            verify(exactly = 1) { mockRepository.existsRootWithUri(uri) }
        }

        @Test
        fun `shouldReread returns false when document exists`() {
            val uri = "test://existing-document"
            every { mockRepository.existsRootWithUri(uri) } returns true

            val result = NeverRefreshExistingDocumentContentPolicy.shouldReread(mockRepository, uri)

            assertFalse(result)
            verify(exactly = 1) { mockRepository.existsRootWithUri(uri) }
        }

        @Test
        fun `shouldRefreshDocument always returns false`() {
            val document = MaterializedDocument(
                id = "doc1",
                uri = "test://document",
                title = "Test Document",
                children = emptyList()
            )

            val result = NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(
                mockRepository,
                document
            )

            assertFalse(result)
            verify(exactly = 0) { mockRepository.existsRootWithUri(any()) }
        }

        @Test
        fun `shouldRefreshDocument returns false even for different documents`() {
            val doc1 = MaterializedDocument(
                id = "doc1",
                uri = "test://document1",
                title = "Document 1",
                children = emptyList()
            )

            val doc2 = MaterializedDocument(
                id = "doc2",
                uri = "test://document2",
                title = "Document 2",
                children = emptyList()
            )

            assertFalse(NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(mockRepository, doc1))
            assertFalse(NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(mockRepository, doc2))
        }

        @Test
        fun `shouldReread handles multiple URIs correctly`() {
            val existingUri = "test://existing"
            val newUri1 = "test://new1"
            val newUri2 = "test://new2"

            every { mockRepository.existsRootWithUri(existingUri) } returns true
            every { mockRepository.existsRootWithUri(newUri1) } returns false
            every { mockRepository.existsRootWithUri(newUri2) } returns false

            assertFalse(NeverRefreshExistingDocumentContentPolicy.shouldReread(mockRepository, existingUri))
            assertTrue(NeverRefreshExistingDocumentContentPolicy.shouldReread(mockRepository, newUri1))
            assertTrue(NeverRefreshExistingDocumentContentPolicy.shouldReread(mockRepository, newUri2))

            verify(exactly = 1) { mockRepository.existsRootWithUri(existingUri) }
            verify(exactly = 1) { mockRepository.existsRootWithUri(newUri1) }
            verify(exactly = 1) { mockRepository.existsRootWithUri(newUri2) }
        }
    }

    @Nested
    inner class IngestUriIfNeededTest {

        private lateinit var ingestCallTracker: MutableList<NavigableDocument>

        @BeforeEach
        fun setUpIngestTracker() {
            ingestCallTracker = mutableListOf()
        }

        @Test
        fun `ingestUriIfNeeded does not read when shouldReread returns false`() {
            val uri = "test://existing-document"
            val policy = NeverRefreshExistingDocumentContentPolicy

            every { mockRepository.existsRootWithUri(uri) } returns true

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri,
                ingestDocument = { ingestCallTracker.add(it) }
            )

            // Should not parse or ingest
            assertTrue(ingestCallTracker.isEmpty())
            verify(exactly = 1) { mockRepository.existsRootWithUri(uri) }
            verify(exactly = 0) { mockReader.parseUrl(any()) }
        }

        @Test
        fun `ingestUriIfNeeded reads but does not ingest when shouldRefreshDocument returns false`() {
            val uri = "test://new-document"
            val document = MaterializedDocument(
                id = "doc1",
                uri = uri,
                title = "Test Document",
                children = emptyList()
            )
            val policy = NeverRefreshExistingDocumentContentPolicy

            every { mockRepository.existsRootWithUri(uri) } returns false
            every { mockReader.parseUrl(uri) } returns document

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri,
                ingestDocument = { ingestCallTracker.add(it) }
            )

            // Should parse but not ingest (because shouldRefreshDocument always returns false)
            assertTrue(ingestCallTracker.isEmpty())
            verify(exactly = 1) { mockRepository.existsRootWithUri(uri) }
            verify(exactly = 1) { mockReader.parseUrl(uri) }
        }

        @Test
        fun `ingestUriIfNeeded ingests when both shouldReread and shouldRefreshDocument return true`() {
            val uri = "test://new-document"
            val document = MaterializedDocument(
                id = "doc1",
                uri = uri,
                title = "Test Document",
                children = emptyList()
            )

            // Create a custom policy that allows refresh
            val policy = object : ContentRefreshPolicy {
                override fun shouldReread(
                    repository: ChunkingContentElementRepository,
                    rootUri: String,
                ): Boolean = true

                override fun shouldRefreshDocument(
                    repository: ChunkingContentElementRepository,
                    root: NavigableDocument,
                ): Boolean = true
            }

            every { mockReader.parseUrl(uri) } returns document

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri,
                ingestDocument = { ingestCallTracker.add(it) }
            )

            // Should parse and ingest
            assertEquals(1, ingestCallTracker.size)
            assertEquals(document, ingestCallTracker[0])
            verify(exactly = 1) { mockReader.parseUrl(uri) }
        }

        @Test
        fun `ingestUriIfNeeded handles multiple URIs independently`() {
            val existingUri = "test://existing"
            val newUri = "test://new"

            val newDocument = MaterializedDocument(
                id = "new-doc",
                uri = newUri,
                title = "New Document",
                children = emptyList()
            )

            val policy = object : ContentRefreshPolicy {
                override fun shouldReread(
                    repository: ChunkingContentElementRepository,
                    rootUri: String,
                ): Boolean = !repository.existsRootWithUri(rootUri)

                override fun shouldRefreshDocument(
                    repository: ChunkingContentElementRepository,
                    root: NavigableDocument,
                ): Boolean = true
            }

            every { mockRepository.existsRootWithUri(existingUri) } returns true
            every { mockRepository.existsRootWithUri(newUri) } returns false
            every { mockReader.parseUrl(newUri) } returns newDocument

            // Try with existing URI
            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = existingUri,
                ingestDocument = { ingestCallTracker.add(it) }
            )

            assertEquals(0, ingestCallTracker.size)

            // Try with new URI
            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = newUri,
                ingestDocument = { ingestCallTracker.add(it) }
            )

            assertEquals(1, ingestCallTracker.size)
            assertEquals(newDocument, ingestCallTracker[0])
        }

        @Test
        fun `ingestUriIfNeeded calls ingestDocument with parsed document`() {
            val uri = "test://document"
            val document = MaterializedDocument(
                id = "doc1",
                uri = uri,
                title = "Test Document",
                children = emptyList()
            )

            val policy = object : ContentRefreshPolicy {
                override fun shouldReread(
                    repository: ChunkingContentElementRepository,
                    rootUri: String,
                ): Boolean = true

                override fun shouldRefreshDocument(
                    repository: ChunkingContentElementRepository,
                    root: NavigableDocument,
                ): Boolean = true
            }

            every { mockReader.parseUrl(uri) } returns document

            var capturedDocument: NavigableDocument? = null
            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri,
                ingestDocument = { capturedDocument = it }
            )

            assertNotNull(capturedDocument)
            assertEquals(document.id, capturedDocument?.id)
            assertEquals(document.uri, capturedDocument?.uri)
            assertEquals(document.title, capturedDocument?.title)
        }

        @Test
        fun `ingestUriIfNeeded with conditional refresh policy`() {
            val uri = "test://conditional-document"
            val document = MaterializedDocument(
                id = "doc1",
                uri = uri,
                title = "Conditional Document",
                children = emptyList()
            )

            // Policy that only refreshes documents with specific title
            val policy = object : ContentRefreshPolicy {
                override fun shouldReread(
                    repository: ChunkingContentElementRepository,
                    rootUri: String,
                ): Boolean = true

                override fun shouldRefreshDocument(
                    repository: ChunkingContentElementRepository,
                    root: NavigableDocument,
                ): Boolean = root.title.contains("Conditional")
            }

            every { mockReader.parseUrl(uri) } returns document

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri,
                ingestDocument = { ingestCallTracker.add(it) }
            )

            assertEquals(1, ingestCallTracker.size)

            // Clear tracker and test with non-matching document
            ingestCallTracker.clear()

            val otherDocument = MaterializedDocument(
                id = "doc2",
                uri = "test://other",
                title = "Other Document",
                children = emptyList()
            )

            every { mockReader.parseUrl("test://other") } returns otherDocument

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = "test://other",
                ingestDocument = { ingestCallTracker.add(it) }
            )

            assertEquals(0, ingestCallTracker.size)
        }

        @Test
        fun `ingestUriIfNeeded handles parseUrl exceptions`() {
            val uri = "test://failing-document"
            val policy = object : ContentRefreshPolicy {
                override fun shouldReread(
                    repository: ChunkingContentElementRepository,
                    rootUri: String,
                ): Boolean = true

                override fun shouldRefreshDocument(
                    repository: ChunkingContentElementRepository,
                    root: NavigableDocument,
                ): Boolean = true
            }

            every { mockReader.parseUrl(uri) } throws RuntimeException("Parse error")

            assertThrows(RuntimeException::class.java) {
                policy.ingestUriIfNeeded(
                    repository = mockRepository,
                    hierarchicalContentReader = mockReader,
                    rootUri = uri,
                    ingestDocument = { ingestCallTracker.add(it) }
                )
            }

            assertTrue(ingestCallTracker.isEmpty())
        }

        @Test
        fun `ingestUriIfNeeded does not call ingestDocument when shouldReread is false`() {
            val uri = "test://no-reread"
            val policy = object : ContentRefreshPolicy {
                override fun shouldReread(
                    repository: ChunkingContentElementRepository,
                    rootUri: String,
                ): Boolean = false

                override fun shouldRefreshDocument(
                    repository: ChunkingContentElementRepository,
                    root: NavigableDocument,
                ): Boolean = true
            }

            var ingestCalled = false
            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri,
                ingestDocument = { ingestCalled = true }
            )

            assertFalse(ingestCalled)
            verify(exactly = 0) { mockReader.parseUrl(any()) }
        }
    }

    @Nested
    inner class CustomPolicyTest {

        @Test
        fun `custom policy can implement different refresh logic`() {
            val uri = "test://custom"
            val document = MaterializedDocument(
                id = "doc1",
                uri = uri,
                title = "Large Document",
                children = emptyList()
            )

            // Policy that never rereads documents with "Large" in the title
            val policy = object : ContentRefreshPolicy {
                override fun shouldReread(
                    repository: ChunkingContentElementRepository,
                    rootUri: String,
                ): Boolean = true

                override fun shouldRefreshDocument(
                    repository: ChunkingContentElementRepository,
                    root: NavigableDocument,
                ): Boolean = !root.title.contains("Large")
            }

            every { mockReader.parseUrl(uri) } returns document

            val ingestCalls = mutableListOf<NavigableDocument>()
            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri,
                ingestDocument = { ingestCalls.add(it) }
            )

            // Should read but not ingest because title contains "Large"
            assertTrue(ingestCalls.isEmpty())
            verify(exactly = 1) { mockReader.parseUrl(uri) }
        }

        @Test
        fun `custom policy can check repository state`() {
            val uri = "test://check-repo"
            val document = MaterializedDocument(
                id = "doc1",
                uri = uri,
                title = "Test",
                children = emptyList()
            )

            // Policy that checks if repository has space
            val policy = object : ContentRefreshPolicy {
                override fun shouldReread(
                    repository: ChunkingContentElementRepository,
                    rootUri: String,
                ): Boolean = repository.count() < 100

                override fun shouldRefreshDocument(
                    repository: ChunkingContentElementRepository,
                    root: NavigableDocument,
                ): Boolean = true
            }

            every { mockRepository.count() } returns 50
            every { mockReader.parseUrl(uri) } returns document

            val ingestCalls = mutableListOf<NavigableDocument>()
            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri,
                ingestDocument = { ingestCalls.add(it) }
            )

            assertEquals(1, ingestCalls.size)

            // Test when repository is full
            ingestCalls.clear()
            every { mockRepository.count() } returns 150

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri,
                ingestDocument = { ingestCalls.add(it) }
            )

            assertTrue(ingestCalls.isEmpty())
        }
    }
}
