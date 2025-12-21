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
import com.embabel.agent.rag.store.DocumentDeletionResult
import io.mockk.*
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
        fun `shouldRefreshDocument always returns true`() {
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

            assertTrue(result)
            verify(exactly = 0) { mockRepository.existsRootWithUri(any()) }
        }

        @Test
        fun `shouldRefreshDocument returns true for different documents`() {
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

            assertTrue(NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(mockRepository, doc1))
            assertTrue(NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(mockRepository, doc2))
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

        @Test
        fun `shouldRefreshDocument returns true regardless of repository state`() {
            val document = MaterializedDocument(
                id = "doc1",
                uri = "test://document",
                title = "Test Document",
                children = emptyList()
            )

            // Test with different repository states - shouldRefreshDocument should always return true
            every { mockRepository.existsRootWithUri(any()) } returns true
            assertTrue(NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(mockRepository, document))

            every { mockRepository.existsRootWithUri(any()) } returns false
            assertTrue(NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(mockRepository, document))

            every { mockRepository.info().contentElementCount } returns 0
            assertTrue(NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(mockRepository, document))

            every { mockRepository.info().contentElementCount } returns 1000
            assertTrue(NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(mockRepository, document))
        }

        @Test
        fun `shouldRefreshDocument returns true for documents with children`() {
            val childDocument = MaterializedDocument(
                id = "child1",
                uri = "test://child",
                title = "Child Document",
                children = emptyList()
            )

            val parentDocument = MaterializedDocument(
                id = "parent",
                uri = "test://parent",
                title = "Parent Document",
                children = listOf(childDocument)
            )

            assertTrue(NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(mockRepository, parentDocument))
        }

        @Test
        fun `shouldRefreshDocument returns true for documents with metadata`() {
            val documentWithTimestamp = MaterializedDocument(
                id = "doc1",
                uri = "test://document",
                title = "Test Document",
                ingestionTimestamp = java.time.Instant.now(),
                children = emptyList()
            )

            assertTrue(
                NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(
                    mockRepository,
                    documentWithTimestamp
                )
            )
        }

        @Test
        fun `ingestUriIfNeeded demonstrates shouldRefreshDocument is reached after successful reread`() {
            val uri = "test://new-document"
            val document = MaterializedDocument(
                id = "doc1",
                uri = uri,
                title = "Test Document",
                children = emptyList()
            )

            every { mockRepository.existsRootWithUri(uri) } returns false
            every { mockReader.parseUrl(uri) } returns document
            every { mockRepository.writeAndChunkDocument(document) } returns emptyList()

            val result = NeverRefreshExistingDocumentContentPolicy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            // Verify flow: shouldReread returned true (document parsed),
            // and shouldRefreshDocument returned true (new document written, no delete for new doc)
            verify(exactly = 2) { mockRepository.existsRootWithUri(uri) } // Once for shouldReread, once for delete check
            verify(exactly = 1) { mockReader.parseUrl(uri) }
            verify(exactly = 0) { mockRepository.deleteRootAndDescendants(uri) } // Not called for new documents
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(document) }
            assertEquals(document, result, "Should return the document when it is refreshed")
        }

        @Test
        fun `multiple calls to shouldRefreshDocument always return true`() {
            val document = MaterializedDocument(
                id = "doc1",
                uri = "test://document",
                title = "Test Document",
                children = emptyList()
            )

            // Call multiple times to ensure consistency
            repeat(10) {
                assertTrue(
                    NeverRefreshExistingDocumentContentPolicy.shouldRefreshDocument(mockRepository, document),
                    "shouldRefreshDocument should always return true, even on repeated calls"
                )
            }
        }
    }

    @Nested
    inner class IngestUriIfNeededTest {

        @Test
        fun `ingestUriIfNeeded does not read when shouldReread returns false`() {
            val uri = "test://existing-document"
            val policy = NeverRefreshExistingDocumentContentPolicy

            every { mockRepository.existsRootWithUri(uri) } returns true

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            // Should not parse or ingest
            verify(exactly = 1) { mockRepository.existsRootWithUri(uri) }
            verify(exactly = 0) { mockReader.parseUrl(any()) }
            verify(exactly = 0) { mockRepository.writeAndChunkDocument(any()) }
        }

        @Test
        fun `ingestUriIfNeeded reads and ingests new document`() {
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
            every { mockRepository.writeAndChunkDocument(document) } returns emptyList()

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            // Should parse and ingest (no delete for new documents)
            verify(exactly = 2) { mockRepository.existsRootWithUri(uri) } // Once for shouldReread, once for delete check
            verify(exactly = 1) { mockReader.parseUrl(uri) }
            verify(exactly = 0) { mockRepository.deleteRootAndDescendants(uri) }
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(document) }
        }

        @Test
        fun `ingestUriIfNeeded ingests when both shouldReread and shouldRefreshDocument return true`() {
            val uri = "test://existing-document"
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

            every { mockRepository.existsRootWithUri(uri) } returns true
            every { mockReader.parseUrl(uri) } returns document
            every { mockRepository.deleteRootAndDescendants(uri) } returns DocumentDeletionResult(uri, 5)
            every { mockRepository.writeAndChunkDocument(document) } returns emptyList()

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            // Should delete old content, then parse and ingest
            verify(exactly = 1) { mockReader.parseUrl(uri) }
            verify(exactly = 1) { mockRepository.deleteRootAndDescendants(uri) }
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(document) }
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
            every { mockRepository.writeAndChunkDocument(newDocument) } returns emptyList()

            // Try with existing URI
            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = existingUri
            )

            verify(exactly = 0) { mockRepository.writeAndChunkDocument(any()) }
            verify(exactly = 0) { mockRepository.deleteRootAndDescendants(any()) }

            // Try with new URI
            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = newUri
            )

            // No delete for new documents, but should write
            verify(exactly = 0) { mockRepository.deleteRootAndDescendants(any()) }
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(newDocument) }
        }

        @Test
        fun `ingestUriIfNeeded calls writeAndChunkDocument with parsed document`() {
            val uri = "test://existing-document"
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

            every { mockRepository.existsRootWithUri(uri) } returns true
            every { mockReader.parseUrl(uri) } returns document
            every { mockRepository.deleteRootAndDescendants(uri) } returns DocumentDeletionResult(uri, 3)
            every { mockRepository.writeAndChunkDocument(document) } returns emptyList()

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            verify(exactly = 1) { mockRepository.deleteRootAndDescendants(uri) }
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(document) }
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

            every { mockRepository.existsRootWithUri(uri) } returns true
            every { mockReader.parseUrl(uri) } returns document
            every { mockRepository.deleteRootAndDescendants(uri) } returns DocumentDeletionResult(uri, 2)
            every { mockRepository.writeAndChunkDocument(document) } returns emptyList()

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            verify(exactly = 1) { mockRepository.deleteRootAndDescendants(uri) }
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(document) }

            // Test with non-matching document
            val otherDocument = MaterializedDocument(
                id = "doc2",
                uri = "test://other",
                title = "Other Document",
                children = emptyList()
            )

            every { mockRepository.existsRootWithUri("test://other") } returns true
            every { mockReader.parseUrl("test://other") } returns otherDocument

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = "test://other"
            )

            // Should still only have 1 call (from the first document) - no delete or write for non-matching
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(any()) }
            verify(exactly = 0) { mockRepository.writeAndChunkDocument(otherDocument) }
            verify(exactly = 1) { mockRepository.deleteRootAndDescendants(any()) }
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
                    rootUri = uri
                )
            }

            verify(exactly = 0) { mockRepository.writeAndChunkDocument(any()) }
        }

        @Test
        fun `ingestUriIfNeeded does not call writeAndChunkDocument when shouldReread is false`() {
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

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            verify(exactly = 0) { mockReader.parseUrl(any()) }
            verify(exactly = 0) { mockRepository.writeAndChunkDocument(any()) }
            verify(exactly = 0) { mockRepository.deleteRootAndDescendants(any()) }
        }

        @Test
        fun `ingestUriIfNeeded deletes old content before writing new content`() {
            val uri = "test://existing-document"
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

            every { mockRepository.existsRootWithUri(uri) } returns true
            every { mockReader.parseUrl(uri) } returns document
            every { mockRepository.deleteRootAndDescendants(uri) } returns DocumentDeletionResult(uri, 10)
            every { mockRepository.writeAndChunkDocument(document) } returns listOf("chunk1", "chunk2")

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            // Verify order: delete must happen before write
            verifyOrder {
                mockRepository.deleteRootAndDescendants(uri)
                mockRepository.writeAndChunkDocument(document)
            }
        }

        @Test
        fun `ingestUriIfNeeded does not delete when document does not exist`() {
            val uri = "test://new-document"
            val document = MaterializedDocument(
                id = "doc1",
                uri = uri,
                title = "New Document",
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

            every { mockRepository.existsRootWithUri(uri) } returns false
            every { mockReader.parseUrl(uri) } returns document
            every { mockRepository.writeAndChunkDocument(document) } returns listOf("chunk1")

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            // Should NOT call delete for new documents, but should write
            verify(exactly = 0) { mockRepository.deleteRootAndDescendants(uri) }
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(document) }
        }
    }

    @Nested
    inner class AlwaysRefreshContentRefreshPolicyTest {

        @Test
        fun `shouldReread always returns true`() {
            val uri = "test://any-document"
            every { mockRepository.existsRootWithUri(uri) } returns true

            val result = AlwaysRefreshContentRefreshPolicy.shouldReread(mockRepository, uri)

            assertTrue(result)
        }

        @Test
        fun `shouldReread returns true even when document does not exist`() {
            val uri = "test://new-document"
            every { mockRepository.existsRootWithUri(uri) } returns false

            val result = AlwaysRefreshContentRefreshPolicy.shouldReread(mockRepository, uri)

            assertTrue(result)
        }

        @Test
        fun `shouldRefreshDocument always returns true`() {
            val document = MaterializedDocument(
                id = "doc1",
                uri = "test://document",
                title = "Test Document",
                children = emptyList()
            )

            val result = AlwaysRefreshContentRefreshPolicy.shouldRefreshDocument(mockRepository, document)

            assertTrue(result)
        }

        @Test
        fun `ingestUriIfNeeded always refreshes existing document`() {
            val uri = "test://existing-document"
            val document = MaterializedDocument(
                id = "doc1",
                uri = uri,
                title = "Test Document",
                children = emptyList()
            )

            every { mockRepository.existsRootWithUri(uri) } returns true
            every { mockReader.parseUrl(uri) } returns document
            every { mockRepository.deleteRootAndDescendants(uri) } returns DocumentDeletionResult(uri, 5)
            every { mockRepository.writeAndChunkDocument(document) } returns emptyList()

            val result = AlwaysRefreshContentRefreshPolicy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            assertEquals(document, result)
            verify(exactly = 1) { mockReader.parseUrl(uri) }
            verify(exactly = 1) { mockRepository.deleteRootAndDescendants(uri) }
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(document) }
        }

        @Test
        fun `ingestUriIfNeeded ingests new document without delete`() {
            val uri = "test://new-document"
            val document = MaterializedDocument(
                id = "doc1",
                uri = uri,
                title = "New Document",
                children = emptyList()
            )

            every { mockRepository.existsRootWithUri(uri) } returns false
            every { mockReader.parseUrl(uri) } returns document
            every { mockRepository.writeAndChunkDocument(document) } returns emptyList()

            val result = AlwaysRefreshContentRefreshPolicy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            assertEquals(document, result)
            verify(exactly = 1) { mockReader.parseUrl(uri) }
            verify(exactly = 0) { mockRepository.deleteRootAndDescendants(uri) }
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(document) }
        }
    }

    @Nested
    inner class UrlSpecificContentRefreshPolicyTest {

        @Test
        fun `shouldReread returns true when document does not exist`() {
            val uri = "test://new-document"
            val policy = UrlSpecificContentRefreshPolicy { false }

            every { mockRepository.existsRootWithUri(uri) } returns false

            val result = policy.shouldReread(mockRepository, uri)

            assertTrue(result, "Should reread when document does not exist")
        }

        @Test
        fun `shouldReread returns true when function returns true for existing document`() {
            val uri = "test://refresh-me"
            val policy = UrlSpecificContentRefreshPolicy { it.contains("refresh") }

            every { mockRepository.existsRootWithUri(uri) } returns true

            val result = policy.shouldReread(mockRepository, uri)

            assertTrue(result, "Should reread when function returns true")
        }

        @Test
        fun `shouldReread returns false when function returns false for existing document`() {
            val uri = "test://keep-me"
            val policy = UrlSpecificContentRefreshPolicy { it.contains("refresh") }

            every { mockRepository.existsRootWithUri(uri) } returns true

            val result = policy.shouldReread(mockRepository, uri)

            assertFalse(result, "Should not reread when function returns false")
        }

        @Test
        fun `shouldRefreshDocument always returns true`() {
            val policy = UrlSpecificContentRefreshPolicy { true }
            val document = MaterializedDocument(
                id = "doc1",
                uri = "test://document",
                title = "Test Document",
                children = emptyList()
            )

            val result = policy.shouldRefreshDocument(mockRepository, document)

            assertTrue(result)
        }

        @Test
        fun `matchingPattern factory creates policy that matches regex`() {
            val policy = UrlSpecificContentRefreshPolicy.matchingPattern(Regex(".*\\.html$"))

            every { mockRepository.existsRootWithUri(any()) } returns true

            assertTrue(policy.shouldReread(mockRepository, "test://page.html"))
            assertFalse(policy.shouldReread(mockRepository, "test://page.json"))
            assertTrue(policy.shouldReread(mockRepository, "test://folder/index.html"))
        }

        @Test
        fun `containingAny factory creates policy that matches substrings`() {
            val policy = UrlSpecificContentRefreshPolicy.containingAny("news", "blog")

            every { mockRepository.existsRootWithUri(any()) } returns true

            assertTrue(policy.shouldReread(mockRepository, "test://news/article"))
            assertTrue(policy.shouldReread(mockRepository, "test://blog/post"))
            assertFalse(policy.shouldReread(mockRepository, "test://static/page"))
        }

        @Test
        fun `ingestUriIfNeeded refreshes matching URIs`() {
            val matchingUri = "test://refresh-this"
            val nonMatchingUri = "test://keep-this"
            val policy = UrlSpecificContentRefreshPolicy { it.contains("refresh") }

            val document = MaterializedDocument(
                id = "doc1",
                uri = matchingUri,
                title = "Test Document",
                children = emptyList()
            )

            every { mockRepository.existsRootWithUri(matchingUri) } returns true
            every { mockRepository.existsRootWithUri(nonMatchingUri) } returns true
            every { mockReader.parseUrl(matchingUri) } returns document
            every { mockRepository.deleteRootAndDescendants(matchingUri) } returns DocumentDeletionResult(
                matchingUri,
                3
            )
            every { mockRepository.writeAndChunkDocument(document) } returns emptyList()

            // Matching URI should be refreshed
            val result1 = policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = matchingUri
            )
            assertEquals(document, result1)

            // Non-matching URI should not be refreshed
            val result2 = policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = nonMatchingUri
            )
            assertNull(result2)

            verify(exactly = 1) { mockReader.parseUrl(matchingUri) }
            verify(exactly = 0) { mockReader.parseUrl(nonMatchingUri) }
        }

        @Test
        fun `ingestUriIfNeeded refreshes new URIs regardless of function result`() {
            val newUri = "test://new-document"
            val policy = UrlSpecificContentRefreshPolicy { false } // Function always returns false

            val document = MaterializedDocument(
                id = "doc1",
                uri = newUri,
                title = "New Document",
                children = emptyList()
            )

            every { mockRepository.existsRootWithUri(newUri) } returns false
            every { mockReader.parseUrl(newUri) } returns document
            every { mockRepository.writeAndChunkDocument(document) } returns emptyList()

            val result = policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = newUri
            )

            assertEquals(document, result, "New documents should always be ingested")
            verify(exactly = 1) { mockReader.parseUrl(newUri) }
            verify(exactly = 0) { mockRepository.deleteRootAndDescendants(newUri) } // No delete for new docs
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(document) }
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

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            // Should read but not ingest because title contains "Large"
            verify(exactly = 1) { mockReader.parseUrl(uri) }
            verify(exactly = 0) { mockRepository.writeAndChunkDocument(any()) }
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
                ): Boolean = repository.info().contentElementCount < 100

                override fun shouldRefreshDocument(
                    repository: ChunkingContentElementRepository,
                    root: NavigableDocument,
                ): Boolean = true
            }

            every { mockRepository.info().contentElementCount } returns 50
            every { mockRepository.existsRootWithUri(uri) } returns true
            every { mockReader.parseUrl(uri) } returns document
            every { mockRepository.deleteRootAndDescendants(uri) } returns DocumentDeletionResult(uri, 3)
            every { mockRepository.writeAndChunkDocument(document) } returns emptyList()

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            verify(exactly = 1) { mockRepository.deleteRootAndDescendants(uri) }
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(document) }

            // Test when repository is full
            every { mockRepository.info().contentElementCount } returns 150

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri
            )

            // Should still only have 1 call (from the first ingest)
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(document) }
            verify(exactly = 1) { mockRepository.deleteRootAndDescendants(uri) }
        }
    }

    @Nested
    inner class TtlContentRefreshPolicyTest {

        @Test
        fun `shouldReread returns true when document does not exist`() {
            val uri = "test://new-document"
            val ttl = java.time.Duration.ofMinutes(30)
            val policy = TtlContentRefreshPolicy(ttl)

            every { mockRepository.findContentRootByUri(uri) } returns null

            val result = policy.shouldReread(mockRepository, uri)

            assertTrue(result)
            verify(exactly = 1) { mockRepository.findContentRootByUri(uri) }
        }

        @Test
        fun `shouldReread returns true when document is older than TTL`() {
            val uri = "test://old-document"
            val ttl = java.time.Duration.ofMinutes(30)
            val policy = TtlContentRefreshPolicy(ttl)

            // Create a document that was ingested 1 hour ago (older than 30 min TTL)
            val oldIngestionTime = java.time.Instant.now().minus(java.time.Duration.ofHours(1))
            val oldDocument = MaterializedDocument(
                id = "old-doc",
                uri = uri,
                title = "Old Document",
                ingestionTimestamp = oldIngestionTime,
                children = emptyList()
            )

            every { mockRepository.findContentRootByUri(uri) } returns oldDocument

            val result = policy.shouldReread(mockRepository, uri)

            assertTrue(result, "Should reread document that is older than TTL")
            verify(exactly = 1) { mockRepository.findContentRootByUri(uri) }
        }

        @Test
        fun `shouldReread returns false when document is within TTL`() {
            val uri = "test://recent-document"
            val ttl = java.time.Duration.ofMinutes(30)
            val policy = TtlContentRefreshPolicy(ttl)

            // Create a document that was ingested 10 minutes ago (within 30 min TTL)
            val recentIngestionTime = java.time.Instant.now().minus(java.time.Duration.ofMinutes(10))
            val recentDocument = MaterializedDocument(
                id = "recent-doc",
                uri = uri,
                title = "Recent Document",
                ingestionTimestamp = recentIngestionTime,
                children = emptyList()
            )

            every { mockRepository.findContentRootByUri(uri) } returns recentDocument

            val result = policy.shouldReread(mockRepository, uri)

            assertFalse(result, "Should not reread document that is within TTL")
            verify(exactly = 1) { mockRepository.findContentRootByUri(uri) }
        }

        @Test
        fun `shouldReread with document just past TTL boundary`() {
            val uri = "test://boundary-document"
            val ttl = java.time.Duration.ofMinutes(30)
            val policy = TtlContentRefreshPolicy(ttl)

            // Create a document that was ingested slightly more than 30 minutes ago
            val boundaryIngestionTime = java.time.Instant.now().minus(ttl).minusMillis(100)
            val boundaryDocument = MaterializedDocument(
                id = "boundary-doc",
                uri = uri,
                title = "Boundary Document",
                ingestionTimestamp = boundaryIngestionTime,
                children = emptyList()
            )

            every { mockRepository.findContentRootByUri(uri) } returns boundaryDocument

            val result = policy.shouldReread(mockRepository, uri)

            // Document is past TTL, should reread
            assertTrue(result, "Should reread document that is past TTL boundary")
        }

        @Test
        fun `shouldRefreshDocument always returns true`() {
            val ttl = java.time.Duration.ofMinutes(30)
            val policy = TtlContentRefreshPolicy(ttl)

            val document = MaterializedDocument(
                id = "doc1",
                uri = "test://document",
                title = "Test Document",
                children = emptyList()
            )

            val result = policy.shouldRefreshDocument(mockRepository, document)

            assertTrue(result, "shouldRefreshDocument should always return true")
        }

        @Test
        fun `factory method creates correct policy`() {
            val ttl = java.time.Duration.ofHours(2)
            val policy = TtlContentRefreshPolicy.of(ttl)

            assertNotNull(policy)
            assertTrue(policy is TtlContentRefreshPolicy)
        }

        @Test
        fun `TTL of zero always refreshes`() {
            val uri = "test://zero-ttl-document"
            val ttl = java.time.Duration.ZERO
            val policy = TtlContentRefreshPolicy(ttl)

            // Even a just-ingested document should be rereread with TTL of zero
            val justIngestedDocument = MaterializedDocument(
                id = "just-ingested",
                uri = uri,
                title = "Just Ingested",
                ingestionTimestamp = java.time.Instant.now(),
                children = emptyList()
            )

            every { mockRepository.findContentRootByUri(uri) } returns justIngestedDocument

            val result = policy.shouldReread(mockRepository, uri)

            assertTrue(result, "Should always reread with zero TTL")
        }

        @Test
        fun `different TTL durations work correctly`() {
            val uri = "test://ttl-test"

            // Test with 1 hour TTL
            val oneHourPolicy = TtlContentRefreshPolicy(java.time.Duration.ofHours(1))

            // Document ingested 30 minutes ago
            val doc30MinAgo = MaterializedDocument(
                id = "doc",
                uri = uri,
                title = "Test",
                ingestionTimestamp = java.time.Instant.now().minus(java.time.Duration.ofMinutes(30)),
                children = emptyList()
            )

            // Document ingested 90 minutes ago
            val doc90MinAgo = MaterializedDocument(
                id = "doc",
                uri = uri,
                title = "Test",
                ingestionTimestamp = java.time.Instant.now().minus(java.time.Duration.ofMinutes(90)),
                children = emptyList()
            )

            every { mockRepository.findContentRootByUri(uri) } returns doc30MinAgo
            assertFalse(oneHourPolicy.shouldReread(mockRepository, uri), "30 min old doc should be within 1 hour TTL")

            every { mockRepository.findContentRootByUri(uri) } returns doc90MinAgo
            assertTrue(oneHourPolicy.shouldReread(mockRepository, uri), "90 min old doc should exceed 1 hour TTL")
        }

        @Test
        fun `ingestUriIfNeeded respects TTL policy`() {
            val uri = "test://ttl-ingestion"
            val ttl = java.time.Duration.ofMinutes(30)
            val policy = TtlContentRefreshPolicy(ttl)

            // Document within TTL
            val recentDocument = MaterializedDocument(
                id = "recent",
                uri = uri,
                title = "Recent",
                ingestionTimestamp = java.time.Instant.now().minus(java.time.Duration.ofMinutes(5)),
                children = emptyList()
            )

            every { mockRepository.findContentRootByUri(uri) } returns recentDocument

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri,
            )

            // Should not parse or ingest since document is within TTL
            verify(exactly = 1) { mockRepository.findContentRootByUri(uri) }
            verify(exactly = 0) { mockReader.parseUrl(any()) }
            verify(exactly = 0) { mockRepository.writeAndChunkDocument(any()) }
        }

        @Test
        fun `ingestUriIfNeeded refreshes expired documents`() {
            val uri = "test://expired-doc"
            val ttl = java.time.Duration.ofMinutes(30)
            val policy = TtlContentRefreshPolicy(ttl)

            // Document older than TTL
            val oldDocument = MaterializedDocument(
                id = "old",
                uri = uri,
                title = "Old",
                ingestionTimestamp = java.time.Instant.now().minus(java.time.Duration.ofHours(1)),
                children = emptyList()
            )

            val newDocument = MaterializedDocument(
                id = "new",
                uri = uri,
                title = "New",
                children = emptyList()
            )

            every { mockRepository.findContentRootByUri(uri) } returns oldDocument
            every { mockRepository.existsRootWithUri(uri) } returns true
            every { mockReader.parseUrl(uri) } returns newDocument
            every { mockRepository.deleteRootAndDescendants(uri) } returns DocumentDeletionResult(uri, 5)
            every { mockRepository.writeAndChunkDocument(any()) } returns emptyList()

            policy.ingestUriIfNeeded(
                repository = mockRepository,
                hierarchicalContentReader = mockReader,
                rootUri = uri,
            )

            // Should delete old content, then parse and ingest since document is expired
            verify(exactly = 1) { mockRepository.findContentRootByUri(uri) }
            verify(exactly = 1) { mockReader.parseUrl(uri) }
            verify(exactly = 1) { mockRepository.deleteRootAndDescendants(uri) }
            verify(exactly = 1) { mockRepository.writeAndChunkDocument(newDocument) }
        }
    }
}
