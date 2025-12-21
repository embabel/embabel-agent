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

import com.embabel.agent.api.common.primitive.KeywordExtractor
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.CONTAINER_SECTION_ID
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.SEQUENCE_NUMBER
import com.embabel.agent.rag.ingestion.RetrievableEnhancer
import com.embabel.agent.rag.model.*
import com.embabel.agent.rag.service.CoreSearchOperations
import com.embabel.agent.rag.service.RagRequest
import com.embabel.agent.rag.service.ResultExpander
import com.embabel.agent.rag.service.support.FunctionRagFacet
import com.embabel.agent.rag.service.support.RagFacet
import com.embabel.agent.rag.service.support.RagFacetProvider
import com.embabel.agent.rag.service.support.RagFacetResults
import com.embabel.agent.rag.store.AbstractChunkingContentElementRepository
import com.embabel.agent.rag.store.ContentElementRepositoryInfo
import com.embabel.agent.rag.store.DocumentDeletionResult
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.util.indent
import com.embabel.common.util.trim
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.*
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.MultiBits
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.TopDocs
import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.store.Directory
import org.apache.lucene.store.FSDirectory
import org.springframework.ai.embedding.EmbeddingModel
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt


/**
 * Lucene RAG facet with optional vector search support via an EmbeddingModel.
 * Supports both in-memory and disk-based persistence.
 * Implements WritableContentElementRepository so we can add to the store.
 *
 * @param name Name of this RAG service
 * @param embeddingModel Optional embedding model for vector search; if null, only text search is
 * supported
 * @param keywordExtractor Optional keyword extractor for keyword-based search; if null, keyword
 * search is disabled
 * @param vectorWeight Weighting for vector similarity in hybrid search (0.0 to 1.0)
 * @param chunkerConfig Configuration for content chunking
 * @param indexPath Optional path for disk-based index storage; if null, uses in-memory storage
 */
class LuceneSearchOperations @JvmOverloads constructor(
    override val name: String,
    override val enhancers: List<RetrievableEnhancer> = emptyList(),
    private val embeddingModel: EmbeddingModel? = null,
    private val keywordExtractor: KeywordExtractor? = null,
    private val vectorWeight: Double = 0.5,
    chunkerConfig: ContentChunker.Config = ContentChunker.DefaultConfig(),
    private val indexPath: Path? = null,
) : RagFacetProvider,
    AbstractChunkingContentElementRepository(chunkerConfig),
    HasInfoString,
    Closeable,
    CoreSearchOperations,
    ResultExpander {

    private val analyzer = StandardAnalyzer()
    private val directory: Directory = indexPath?.let { FSDirectory.open(it) } ?: ByteBuffersDirectory()
    private val indexWriterConfig = IndexWriterConfig(analyzer)
    private var indexWriter = IndexWriter(directory, indexWriterConfig)
    private val queryParser = QueryParser("content", analyzer)

    override val luceneSyntaxNotes: String = "Full support"

    companion object {
        const val KEYWORDS_FIELD = "keywords"
        private const val CONTENT_FIELD = "content"
        private const val ID_FIELD = "id"
        private const val ELEMENT_TYPE_FIELD = "_element_type"
        private const val TITLE_FIELD = "title"
        private const val URI_FIELD = "uri"
        private const val PARENT_ID_FIELD = "parentId"
        private const val TEXT_FIELD = "text"
        private const val INGESTION_TIMESTAMP_FIELD = "ingestionTimestamp"

        // Element type values
        private const val TYPE_CHUNK = "Chunk"
        private const val TYPE_LEAF_SECTION = "LeafSection"
        private const val TYPE_CONTAINER_SECTION = "ContainerSection"
        private const val TYPE_DOCUMENT = "Document"

        @JvmStatic
        fun builder(): LuceneSearchOperationsBuilder = LuceneSearchOperationsBuilder()

        @JvmStatic
        fun withName(name: String): LuceneSearchOperationsBuilder =
            LuceneSearchOperationsBuilder().withName(name)
    }

    init {
        if (embeddingModel == null) {
            logger.warn("No embedding model configured; only text search will be supported.")
        }

        if (indexPath != null) {
            logger.info("Using disk-based Lucene index at: {}", indexPath)
            // Defer chunk loading until after object is fully constructed
        } else {
            logger.info("Using in-memory Lucene index")
        }
    }

    // Lazy initialization of existing chunks - called on first access
    private var chunksLoaded = false
    private fun ensureChunksLoaded() {
        if (!chunksLoaded && indexPath != null) {
            logger.info("Triggering lazy loading of existing chunks...")
            loadExistingChunks()
            chunksLoaded = true
        }
    }

    /**
     * Manually trigger loading of existing chunks from disk.
     * Useful for ensuring chunks are loaded immediately after startup.
     */
    fun loadExistingChunksFromDisk(): LuceneSearchOperations {
        logger.info("Manually triggering chunk loading from disk...")
        ensureChunksLoaded()
        return this
    }

    @Volatile
    private var directoryReader: DirectoryReader? = null

    private val contentElementStorage = ConcurrentHashMap<String, ContentElement>()

    override fun facets(): List<RagFacet<out Retrievable>> {
        return listOf(
            FunctionRagFacet(
                name = "$name.hybrid",
                searchFunction = ::hybridSearch,
            ),
            FunctionRagFacet(
                name = "$name.keywords",
                searchFunction = ::keywordSearch,
            )
        )
    }

    override fun findAllChunksById(chunkIds: List<String>): List<Chunk> {
        logger.debug("Finding chunks by IDs: {}", chunkIds)

        val foundChunks = chunkIds.mapNotNull { chunkId ->
            contentElementStorage[chunkId] as? Chunk
        }

        logger.debug("Found {}/{} chunks by id", foundChunks.size, chunkIds.size)
        return foundChunks
    }

    override fun findChunksForEntity(entityId: String): List<Chunk> {
        TODO("Entities not supported in LuceneRagService")
    }

    override fun findById(id: String): ContentElement? {
        return contentElementStorage[id]
    }

    override fun save(element: ContentElement): ContentElement {
        contentElementStorage[element.id] = element
        // Persist structural elements to Lucene (Chunks are handled separately in onNewRetrievable)
        if (element !is Chunk) {
            persistStructuralElement(element)
        }
        return element
    }

    /**
     * Persist a structural element (Document, Section, etc.) to Lucene for recovery after restart.
     * Chunks are handled separately via onNewRetrievable which also handles embeddings.
     */
    private fun persistStructuralElement(element: ContentElement) {
        val luceneDoc = Document().apply {
            add(StringField(ID_FIELD, element.id, Field.Store.YES))

            // Store element type for reconstruction
            val elementType = when (element) {
                is NavigableDocument -> TYPE_DOCUMENT
                is LeafSection -> TYPE_LEAF_SECTION
                is ContainerSection -> TYPE_CONTAINER_SECTION
                else -> element.javaClass.simpleName
            }
            add(StringField(ELEMENT_TYPE_FIELD, elementType, Field.Store.YES))

            // Store common fields
            if (element is HierarchicalContentElement) {
                element.parentId?.let { add(StoredField(PARENT_ID_FIELD, it)) }
            }

            // Title is on Section and ContentRoot
            when (element) {
                is Section -> add(StoredField(TITLE_FIELD, element.title))
                is ContentRoot -> add(StoredField(TITLE_FIELD, element.title))
            }

            if (element is ContentRoot) {
                add(StoredField(URI_FIELD, element.uri))
                add(StoredField(INGESTION_TIMESTAMP_FIELD, element.ingestionTimestamp.toString()))
            }

            if (element is LeafSection) {
                add(StoredField(TEXT_FIELD, element.text))
            }

            // Store metadata
            element.metadata.forEach { (key, value) ->
                if (value != null) {
                    add(StringField(key, value.toString(), Field.Store.YES))
                }
            }
        }
        indexWriter.addDocument(luceneDoc)
        logger.debug("Persisted structural element id='{}' type='{}'", element.id, element.javaClass.simpleName)
    }

    override fun createInternalRelationships(root: NavigableDocument) {
        // No op here
    }

    fun <T : ContentElement> findAll(clazz: Class<T>): List<T> {
        ensureChunksLoaded()
        logger.debug("Retrieving all content elements from storage")
        val allChunks = contentElementStorage.values
            .filterIsInstance(clazz)
            .sortedBy { "${it.metadata[CONTAINER_SECTION_ID]}-${it.metadata[SEQUENCE_NUMBER]}" }
        logger.debug("Retrieved {} chunks from storage", allChunks.size)
        return allChunks
    }

    /**
     * Default to finding chunks
     */
    fun findAll(): List<Chunk> = findAll(Chunk::class.java)

    /**
     * Update keywords for existing chunks.
     * This will re-index the chunks with new keywords.
     *
     * @param updates Map of chunkId to new keywords
     */
    fun updateKeywords(updates: Map<String, List<String>>) {
        logger.debug("Updating keywords for {} chunks", updates.size)

        var updatedCount = 0
        updates.forEach { (chunkId, newKeywords) ->
            val chunk = contentElementStorage[chunkId] as? Chunk
            if (chunk == null) {
                logger.warn("Chunk with id='{}' not found, skipping keyword update", chunkId)
                return@forEach
            }

            // Update chunk in storage with new keywords
            val updatedChunk = Chunk(
                id = chunk.id,
                text = chunk.text,
                parentId = chunk.parentId,
                metadata = chunk.metadata + (KEYWORDS_FIELD to newKeywords)
            )
            contentElementStorage[chunkId] = updatedChunk

            // Delete old document from Lucene index
            indexWriter.deleteDocuments(org.apache.lucene.index.Term(ID_FIELD, chunkId))

            // Create new Lucene document with updated keywords
            val luceneDoc = Document().apply {
                add(StringField(ID_FIELD, chunk.id, Field.Store.YES))
                add(TextField(CONTENT_FIELD, chunk.embeddableValue(), Field.Store.YES))

                // Add new keywords
                newKeywords.forEach { keyword ->
                    add(TextField(KEYWORDS_FIELD, keyword.lowercase(), Field.Store.YES))
                }

                if (embeddingModel != null && chunk.metadata.containsKey("embedding")) {
                    // Preserve existing embedding if it exists
                    val embedding = embeddingModel.embed(chunk.embeddableValue())
                    val embeddingBytes = floatArrayToBytes(embedding)
                    add(StoredField("embedding", embeddingBytes))
                }

                chunk.metadata.forEach { (key, value) ->
                    if (key != KEYWORDS_FIELD) {
                        add(StringField(key, value.toString(), Field.Store.YES))
                    }
                }
            }
            indexWriter.addDocument(luceneDoc)

            updatedCount++
            logger.debug("Updated keywords for chunk id='{}' to: {}", chunkId, newKeywords)
        }

        // Commit changes if anything was updated
        if (updatedCount > 0) {
            commit()
        }
        logger.info("Successfully updated keywords for {} chunks", updatedCount)
    }

    /**
     * Find chunk IDs by keyword intersection.
     * Returns pairs of (chunkId, matchCount) sorted by match count descending.
     *
     * @param minIntersection Minimum number of keywords that must match (default: 1)
     * @param maxResults Maximum number of results to return (default: 100)
     * @return List of (chunkId, matchCount) pairs sorted by match count descending
     */
    fun findChunkIdsByKeywords(
        keywords: Set<String>,
        minIntersection: Int = 1,
        maxResults: Int = 100,
    ): List<Pair<String, Int>> {
        if (keywords.isEmpty()) {
            logger.warn("No keywords provided for keyword search")
            return emptyList()
        }
        refreshReaderIfNeeded()

        val reader = directoryReader ?: return emptyList()

        // Handle empty index
        if (reader.maxDoc() == 0) {
            return emptyList()
        }

        val searcher = IndexSearcher(reader)

        // Count keyword matches per document
        val docMatchCounts = mutableMapOf<Int, Int>()

        keywords.forEach { keyword ->
            val query = QueryParser(KEYWORDS_FIELD, analyzer).parse(QueryParser.escape(keyword.lowercase()))
            val topDocs = searcher.search(query, reader.maxDoc())

            topDocs.scoreDocs.forEach { scoreDoc ->
                docMatchCounts[scoreDoc.doc] = docMatchCounts.getOrDefault(scoreDoc.doc, 0) + 1
            }
        }

        // Filter by minimum intersection and convert to (chunkId, matchCount) pairs
        return docMatchCounts
            .filter { (_, matchCount) -> matchCount >= minIntersection }
            .map { (docId, matchCount) ->
                val doc = searcher.storedFields().document(docId)
                doc.get(ID_FIELD) to matchCount
            }
            .sortedByDescending { it.second }
            .take(maxResults)
    }

    fun keywordSearch(ragRequest: RagRequest): RagFacetResults<Chunk> {
        if (keywordExtractor == null || keywordExtractor.keywords.isEmpty()) {
            logger.warn("Keyword search requested but no keywords configured")
            return RagFacetResults(
                facetName = name,
                results = emptyList()
            )
        }
        ensureChunksLoaded()
        refreshReaderIfNeeded()

        val extractedKeywords = keywordExtractor.extractKeywords(ragRequest.query)

        val results = findChunkIdsByKeywords(
            keywords = extractedKeywords,
            minIntersection = 1,
            maxResults = ragRequest.topK * 5
        )
        val similarityResults = results
            .mapNotNull { (chunkId, matchCount) ->
                val chunk = contentElementStorage[chunkId] as? Chunk ?: return@mapNotNull null
                SimpleSimilaritySearchResult(
                    match = chunk,
                    score = keywordExtractor.matchCountToScore(matchCount),
                )
            }
            .filter { it.score >= ragRequest.similarityThreshold }
            .sortedByDescending { it.score }
            .take(ragRequest.topK)
        logger.info(
            "Keyword search for query '{}' with keywords [{}] found {} results: {}",
            ragRequest.query,
            extractedKeywords,
            similarityResults.size,
            similarityResults.map { "(${it.match.id}, score=${"%.2f".format(it.score)})" },
        )
        return RagFacetResults(
            facetName = "$name.vector",
            results = similarityResults,
        )
    }

    fun hybridSearch(ragRequest: RagRequest): RagFacetResults<Chunk> {
        ensureChunksLoaded()
        refreshReaderIfNeeded()

        val reader = directoryReader ?: return RagFacetResults(
            facetName = "$name.hybrid",
            results = emptyList()
        )

        val searcher = IndexSearcher(reader)

        // Perform hybrid search: text + vector similarity
        val results = if (embeddingModel != null) {
            val r = performHybridSearch(searcher, ragRequest)
            logger.debug("Hybrid search for query {} found\n{}", ragRequest.query, r)
            r
        } else {
            val r = performTextSearch(searcher, ragRequest)
            logger.debug("Text search for query {} found\n{}", ragRequest.query, r)
            r
        }

        val similarityResults = results
            .filter { it.score >= ragRequest.similarityThreshold }
            .sortedByDescending { it.score }
            .take(ragRequest.topK)

        logger.info(
            "Hybrid search for query {} found {} results: {}",
            ragRequest.query,
            similarityResults.size,
            similarityResults.map { "(${it.match.id}, score=${"%.2f".format(it.score)})" },
        )
        return RagFacetResults(
            facetName = name,
            results = similarityResults
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> vectorSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (embeddingModel == null) {
            logger.warn("Vector search requested but no embedding model configured")
            return emptyList()
        }
        ensureChunksLoaded()
        refreshReaderIfNeeded()

        val reader = directoryReader ?: return emptyList()
        val searcher = IndexSearcher(reader)

        val ragRequest = RagRequest(
            query = request.query,
            similarityThreshold = request.similarityThreshold,
            topK = request.topK,
        )
        val results = performHybridSearch(searcher, ragRequest)
            .filter { clazz.isInstance(it.match) }
            .map { SimpleSimilaritySearchResult(match = it.match as T, score = it.score) }

        logger.info(
            "Vector search for query '{}' found {} results",
            request.query,
            results.size,
        )
        return results
    }

    override fun expandResult(
        id: String,
        method: ResultExpander.Method,
        elementsToAdd: Int,
    ): List<ContentElement> {
        ensureChunksLoaded()

        val contentElement = contentElementStorage[id]
        if (contentElement == null) {
            logger.warn("Chunk with id='{}' not found for expansion", id)
            return emptyList()
        }

        return when (method) {
            ResultExpander.Method.SEQUENCE -> {
                if (contentElement !is Chunk) {
                    logger.warn("Content element id='{}' is not a Chunk; cannot expand by sequence", id)
                    return emptyList()
                }
                expandBySequence(contentElement, elementsToAdd)
            }

            ResultExpander.Method.ZOOM_OUT -> {
                val parentId = when (contentElement) {
                    is Chunk -> contentElement.parentId
                    is HierarchicalContentElement -> contentElement.parentId
                    else -> null
                }
                if (parentId != null) {
                    val parentElement = contentElementStorage[parentId]
                    if (parentElement != null) {
                        listOf(parentElement)
                    } else {
                        logger.warn("Parent element with id='{}' not found for zoom out expansion", parentId)
                        emptyList()
                    }
                } else {
                    logger.warn("Content element id='{}' has no parentId for zoom out expansion", id)
                    emptyList()
                }
            }
        }
    }

    /**
     * Expand a chunk by finding adjacent chunks in the same container section.
     * Returns chunks ordered by sequence number, with the original chunk included.
     */
    private fun expandBySequence(
        chunk: Chunk,
        chunksToAdd: Int,
    ): List<Chunk> {
        val containerSectionId = chunk.metadata[CONTAINER_SECTION_ID]?.toString()
        val sequenceNumber = chunk.metadata[SEQUENCE_NUMBER]?.toString()?.toIntOrNull()

        if (containerSectionId == null || sequenceNumber == null) {
            logger.warn(
                "Chunk id='{}' missing required metadata for sequence expansion: containerSectionId={}, sequenceNumber={}",
                chunk.id,
                containerSectionId,
                sequenceNumber
            )
            return listOf(chunk)
        }

        // Find all chunks in the same container section
        val chunksInSection = contentElementStorage.values
            .filterIsInstance<Chunk>()
            .filter { it.metadata[CONTAINER_SECTION_ID]?.toString() == containerSectionId }
            .mapNotNull { c ->
                val seqNum = c.metadata[SEQUENCE_NUMBER]?.toString()?.toIntOrNull()
                if (seqNum != null) c to seqNum else null
            }
            .sortedBy { it.second }

        if (chunksInSection.isEmpty()) {
            return listOf(chunk)
        }

        // Find index of current chunk
        val currentIndex = chunksInSection.indexOfFirst { it.first.id == chunk.id }
        if (currentIndex == -1) {
            return listOf(chunk)
        }

        // Calculate range to include: chunksToAdd before and after
        val startIndex = (currentIndex - chunksToAdd).coerceAtLeast(0)
        val endIndex = (currentIndex + chunksToAdd).coerceAtMost(chunksInSection.size - 1)

        val expandedChunks = chunksInSection
            .subList(startIndex, endIndex + 1)
            .map { it.first }

        logger.debug(
            "Expanded chunk id='{}' (seq={}) to {} chunks (seq range {}-{})",
            chunk.id,
            sequenceNumber,
            expandedChunks.size,
            expandedChunks.firstOrNull()?.metadata?.get(SEQUENCE_NUMBER),
            expandedChunks.lastOrNull()?.metadata?.get(SEQUENCE_NUMBER)
        )

        return expandedChunks
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> textSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        ensureChunksLoaded()
        refreshReaderIfNeeded()

        val reader = directoryReader ?: return emptyList()
        val searcher = IndexSearcher(reader)

        val ragRequest = RagRequest(
            query = request.query,
            similarityThreshold = request.similarityThreshold,
            topK = request.topK,
        )
        val results = performTextSearch(searcher, ragRequest)
            .filter { clazz.isInstance(it.match) }
            .map { SimpleSimilaritySearchResult(match = it.match as T, score = it.score) }

        logger.info(
            "Text search for query '{}' found {} results",
            request.query,
            results.size,
        )
        return results
    }

    private fun performTextSearch(
        searcher: IndexSearcher,
        ragRequest: RagRequest,
    ): List<SimpleSimilaritySearchResult<Chunk>> {
        val query: Query = queryParser.parse(QueryParser.escape(ragRequest.query))
        val topDocs: TopDocs = searcher.search(query, ragRequest.topK)

        return topDocs.scoreDocs.map { scoreDoc ->
            val doc = searcher.doc(scoreDoc.doc)
            val retrievable = createChunkFromLuceneDocument(doc)
            SimpleSimilaritySearchResult(
                match = retrievable,
                score = scoreDoc.score.toDouble()
            )
        }
    }

    private fun performHybridSearch(
        searcher: IndexSearcher,
        ragRequest: RagRequest,
    ): List<SimpleSimilaritySearchResult<Chunk>> {
        val textQuery: Query = queryParser.parse(QueryParser.escape(ragRequest.query))
        val textResults: TopDocs = searcher.search(textQuery, (ragRequest.topK * 2).coerceAtLeast(20))

        // Get query embedding
        val queryEmbedding = embeddingModel!!.embed(ragRequest.query)

        // Calculate hybrid scores
        val hybridResults = mutableListOf<SimpleSimilaritySearchResult<Chunk>>()

        for (scoreDoc in textResults.scoreDocs) {
            val doc = searcher.doc(scoreDoc.doc)
            val retrievable = createChunkFromLuceneDocument(doc)

            // Get text similarity (normalized)
            val textScore = scoreDoc.score.toDouble()
            val normalizedTextScore = minOf(1.0, textScore / 10.0) // Rough normalization

            // Calculate vector similarity if embedding exists
            val vectorScore = doc.getBinaryValue("embedding")?.let { embeddingBytes ->
                val docEmbedding = bytesToFloatArray(embeddingBytes.bytes)
                cosineSimilarity(queryEmbedding, docEmbedding)
            } ?: 0.0

            // Combine scores with weighting
            val hybridScore = (1 - vectorWeight) * normalizedTextScore + vectorWeight * vectorScore

            hybridResults.add(
                SimpleSimilaritySearchResult(
                    match = retrievable,
                    score = hybridScore
                )
            )
        }

        return hybridResults
    }

    private fun createChunkFromLuceneDocument(luceneDocument: Document): Chunk {
        val keywords = luceneDocument.getValues(KEYWORDS_FIELD)?.toList() ?: emptyList()

        val metadata = luceneDocument.fields
            .filter { field -> field.name() !in setOf(ID_FIELD, CONTENT_FIELD, "embedding", KEYWORDS_FIELD) }
            .associate { field -> field.name() to field.stringValue() as Any? }
            .toMutableMap()

        // Add keywords to metadata if present
        if (keywords.isNotEmpty()) {
            metadata[KEYWORDS_FIELD] = keywords as Any?
        }

        return Chunk(
            id = luceneDocument.get(ID_FIELD),
            text = luceneDocument.get(CONTENT_FIELD),
            parentId = luceneDocument.get(ID_FIELD),
            metadata = metadata,
        )
    }

    /**
     * Rebuild parent-child relationships after loading elements from disk.
     * This replaces container elements with new instances that have their children populated.
     * Uses bottom-up approach to ensure nested children are resolved before their parents.
     */
    private fun rebuildHierarchy() {
        // Build a map of parentId -> children
        val childrenByParentId = mutableMapOf<String, MutableList<NavigableSection>>()

        contentElementStorage.values
            .filterIsInstance<NavigableSection>()
            .filter { (it as? HierarchicalContentElement)?.parentId != null }
            .forEach { section ->
                val parentId = (section as HierarchicalContentElement).parentId!!
                childrenByParentId.getOrPut(parentId) { mutableListOf() }.add(section)
            }

        // Recursively rebuild a container with its children
        fun rebuildContainer(id: String): ContentElement? {
            val element = contentElementStorage[id] ?: return null

            // Get direct children for this element
            val directChildren = childrenByParentId[id] ?: emptyList()

            // Recursively rebuild any container children first
            val rebuiltChildren = directChildren.map { child ->
                when (child) {
                    is DefaultMaterializedContainerSection -> {
                        val rebuilt = rebuildContainer(child.id)
                        (rebuilt as? NavigableSection) ?: child
                    }

                    else -> child
                }
            }

            // Now rebuild this element with its children
            return when (element) {
                is DefaultMaterializedContainerSection -> {
                    if (rebuiltChildren.isNotEmpty()) {
                        element.copy(children = rebuiltChildren).also {
                            contentElementStorage[id] = it
                        }
                    } else element
                }

                is MaterializedDocument -> {
                    if (rebuiltChildren.isNotEmpty()) {
                        element.copy(children = rebuiltChildren).also {
                            contentElementStorage[id] = it
                        }
                    } else element
                }

                else -> element
            }
        }

        // Rebuild from roots (documents)
        contentElementStorage.values
            .filterIsInstance<MaterializedDocument>()
            .forEach { doc -> rebuildContainer(doc.id) }

        // Also rebuild any orphaned container sections (those without a document parent in storage)
        contentElementStorage.values
            .filterIsInstance<DefaultMaterializedContainerSection>()
            .filter { section ->
                section.parentId == null || contentElementStorage[section.parentId] !is NavigableContainerSection
            }
            .forEach { section -> rebuildContainer(section.id) }

        logger.debug("Rebuilt hierarchy for {} containers", childrenByParentId.size)
    }

    /**
     * Create the appropriate ContentElement type from a Lucene document based on stored type.
     */
    private fun createContentElementFromLuceneDocument(
        luceneDocument: Document,
        elementType: String?,
    ): ContentElement? {
        val id = luceneDocument.get(ID_FIELD) ?: return null

        // Extract common metadata (excluding reserved fields)
        val reservedFields = setOf(
            ID_FIELD, CONTENT_FIELD, ELEMENT_TYPE_FIELD, TITLE_FIELD,
            URI_FIELD, PARENT_ID_FIELD, TEXT_FIELD, INGESTION_TIMESTAMP_FIELD,
            KEYWORDS_FIELD, "embedding"
        )
        val metadata = luceneDocument.fields
            .filter { field -> field.name() !in reservedFields }
            .associate { field -> field.name() to (field.stringValue() as Any?) }

        return when (elementType) {
            TYPE_DOCUMENT -> {
                MaterializedDocument(
                    id = id,
                    uri = luceneDocument.get(URI_FIELD) ?: "",
                    title = luceneDocument.get(TITLE_FIELD) ?: "",
                    ingestionTimestamp = luceneDocument.get(INGESTION_TIMESTAMP_FIELD)?.let {
                        java.time.Instant.parse(it)
                    } ?: java.time.Instant.now(),
                    children = emptyList(), // Children are loaded separately
                    metadata = metadata
                )
            }

            TYPE_LEAF_SECTION -> {
                LeafSection(
                    id = id,
                    title = luceneDocument.get(TITLE_FIELD) ?: "",
                    text = luceneDocument.get(TEXT_FIELD) ?: "",
                    parentId = luceneDocument.get(PARENT_ID_FIELD),
                    metadata = metadata
                )
            }

            TYPE_CONTAINER_SECTION -> {
                DefaultMaterializedContainerSection(
                    id = id,
                    title = luceneDocument.get(TITLE_FIELD) ?: "",
                    children = emptyList(), // Children are loaded separately
                    parentId = luceneDocument.get(PARENT_ID_FIELD),
                    metadata = metadata
                )
            }

            null, TYPE_CHUNK -> {
                // No type field means it's a legacy chunk or explicitly a chunk
                createChunkFromLuceneDocument(luceneDocument)
            }

            else -> {
                logger.warn("Unknown element type '{}' for id='{}', treating as Chunk", elementType, id)
                createChunkFromLuceneDocument(luceneDocument)
            }
        }
    }

    override fun onNewRetrievables(retrievables: List<Retrievable>) {
        retrievables.forEach { onNewRetrievable(it) }
    }

    private fun onNewRetrievable(
        retrievable: Retrievable,
    ) {
        // Only process Chunks here - structural elements are persisted via save() -> persistStructuralElement()
        if (retrievable !is Chunk) {
            logger.debug(
                "Skipping non-Chunk retrievable with id='{}' (type={})",
                retrievable.id,
                retrievable.javaClass.simpleName
            )
            return
        }

        // Get keywords from metadata only
        val keywords = when (val keywordsMeta = retrievable.metadata[KEYWORDS_FIELD]) {
            is Collection<*> -> keywordsMeta.filterIsInstance<String>()
            is String -> listOf(keywordsMeta)
            else -> emptyList()
        }

        contentElementStorage[retrievable.id] = retrievable

        // Create Lucene document for indexing
        val luceneDoc = Document().apply {
            add(StringField(ID_FIELD, retrievable.id, Field.Store.YES))
            add(TextField(CONTENT_FIELD, retrievable.embeddableValue(), Field.Store.YES))

            // Add keywords as a multi-valued field
            keywords.forEach { keyword ->
                add(TextField(KEYWORDS_FIELD, keyword.lowercase(), Field.Store.YES))
            }

            if (embeddingModel != null) {
                try {
                    val embedding = embeddingModel.embed(retrievable.embeddableValue())
                    val embeddingBytes = floatArrayToBytes(embedding)
                    add(StoredField("embedding", embeddingBytes))
                    logger.info("Added embedding for retrievable with id {}", retrievable.id)
                } catch (e: Exception) {
                    logger.warn(
                        "Unable to generate embedding for retrievable id='{}': {}",
                        retrievable.id,
                        e.message,
                        e
                    )
                }
            }

            retrievable.metadata.forEach { (key, value) ->
                if (key != KEYWORDS_FIELD) { // Don't duplicate keywords field
                    add(StringField(key, value.toString(), Field.Store.YES))
                }
            }
        }
        indexWriter.addDocument(luceneDoc)
        logger.debug(
            "Indexed and stored retrievable with id='{}', text length={}, keywords={}",
            retrievable.id,
            retrievable.embeddableValue().length,
            keywords
        )
    }

    override fun commit() {
        indexWriter.flush()  // Ensure all changes are written to storage
        indexWriter.commit() // Commit the transaction
        invalidateReader()   // Force reader refresh on next access
    }

    // Vector similarity utility functions
    private fun cosineSimilarity(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        if (a.size != b.size) return 0.0

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in a.indices) {
            dotProduct += (a[i] * b[i]).toDouble()
            normA += (a[i] * a[i]).toDouble()
            normB += (b[i] * b[i]).toDouble()
        }

        return if (normA == 0.0 || normB == 0.0) 0.0 else dotProduct / (sqrt(normA) * sqrt(normB))
    }

    private fun floatArrayToBytes(floatArray: FloatArray): ByteArray {
        val bytes = ByteArray(floatArray.size * 4)
        var index = 0
        for (f in floatArray) {
            val bits = java.lang.Float.floatToIntBits(f)
            bytes[index++] = (bits shr 24).toByte()
            bytes[index++] = (bits shr 16).toByte()
            bytes[index++] = (bits shr 8).toByte()
            bytes[index++] = bits.toByte()
        }
        return bytes
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val floats = FloatArray(bytes.size / 4)
        var index = 0
        for (i in floats.indices) {
            val bits = ((bytes[index++].toInt() and 0xFF) shl 24) or
                    ((bytes[index++].toInt() and 0xFF) shl 16) or
                    ((bytes[index++].toInt() and 0xFF) shl 8) or
                    (bytes[index++].toInt() and 0xFF)
            floats[i] = java.lang.Float.intBitsToFloat(bits)
        }
        return floats
    }

    private fun loadExistingChunks() {
        logger.info("Starting to load existing chunks from disk index...")
        try {
            // Use a separate directory instance for reading to avoid writer conflicts
            val readDirectory = FSDirectory.open(indexPath!!)

            try {
                // Open a reader on the separate directory
                logger.info("Opening DirectoryReader to read from disk")
                val reader = DirectoryReader.open(readDirectory)

                logger.info(
                    "Successfully opened reader. Index has {} documents (maxDoc: {})",
                    reader.numDocs(),
                    reader.maxDoc()
                )

                // Load all existing documents from the index, skipping deleted documents
                val liveDocs = MultiBits.getLiveDocs(reader)
                for (i in 0 until reader.maxDoc()) {
                    // Skip deleted documents (liveDocs.get(i) returns true if doc is live)
                    if (liveDocs != null && !liveDocs.get(i)) {
                        logger.debug("Skipping deleted document at position {}", i)
                        continue
                    }

                    try {
                        val doc = reader.storedFields().document(i)
                        val elementType = doc.get(ELEMENT_TYPE_FIELD)
                        val id = doc.get(ID_FIELD)

                        logger.debug(
                            "Loading document {}: id={}, type={}, content preview={}",
                            i,
                            id,
                            elementType,
                            trim(s = doc.get(CONTENT_FIELD) ?: "", max = 25, keepRight = 4),
                        )

                        val element = createContentElementFromLuceneDocument(doc, elementType)
                        if (element != null) {
                            contentElementStorage[element.id] = element
                            logger.info(
                                "✅ Loaded {} with id={}",
                                elementType ?: "Chunk",
                                element.id,
                            )
                        }
                    } catch (e: Exception) {
                        logger.error("❌ Failed to load document {}: {}", i, e.message, e)
                    }
                }

                reader.close()

                // Rebuild parent-child relationships
                rebuildHierarchy()

                logger.info(
                    "✅ Loaded {} existing elements from disk index {}",
                    contentElementStorage.size,
                    indexPath,
                )

            } finally {
                readDirectory.close()
            }

        } catch (e: Exception) {
            logger.error("Error loading existing chunks from disk index: {}", e.message, e)
        }
    }

    private fun refreshReaderIfNeeded() {
        synchronized(this) {
            try {
                // Always try to open a fresh reader to ensure we see latest changes
                directoryReader?.close()
                directoryReader = DirectoryReader.open(directory)
            } catch (_: Exception) {
                // Index might be empty, which is fine
            }
        }
    }

    private fun invalidateReader() {
        synchronized(this) {
            directoryReader?.close()
            directoryReader = null
        }
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        val docCount = try {
            refreshReaderIfNeeded()
            directoryReader?.numDocs() ?: 0
        } catch (_: Exception) {
            0
        }

        val chunkCount = contentElementStorage.size
        val storageType = if (indexPath != null) "disk" else "memory"
        val basicInfo = "LuceneRagService: $name ($docCount documents, $chunkCount chunks, $storageType)"

        return if (verbose == true) {
            val embeddingInfo = if (embeddingModel != null) "with embeddings" else "text-only"
            val vectorWeightInfo = if (embeddingModel != null) ", vector weight: $vectorWeight" else ""
            val pathInfo = if (indexPath != null) ", path: $indexPath" else ""
            "$basicInfo ($embeddingInfo$vectorWeightInfo$pathInfo)".indent(indent)
        } else {
            basicInfo.indent(indent)
        }
    }

    override fun close() {
        try {
            // Ensure all pending changes are committed before closing
            commit()
        } catch (e: Exception) {
            logger.warn("Error committing changes during close: {}", e.message)
        }

        directoryReader?.close()
        indexWriter.close()
        directory.close()
        analyzer.close()
        contentElementStorage.clear()
    }

    override fun deleteRootAndDescendants(uri: String): DocumentDeletionResult? {
        logger.info("Deleting document with URI: {}", uri)
        synchronized(this) {
            // Find the root document with this URI
            val root = contentElementStorage.values.find {
                it.uri == uri && it.labels().any { label ->
                    label.contains("Document") || label.contains("ContentRoot")
                }
            }

            if (root == null) {
                logger.warn("No document found with URI: {}", uri)
                return null
            }

            logger.debug("Found root document with id: {}", root.id)

            // Find all elements to delete: root and all descendants (by URI or parent relationships)
            val toDelete = mutableSetOf<String>()
            toDelete.add(root.id)

            // Add all elements with the same URI
            contentElementStorage.values.forEach { element ->
                if (element.uri == uri) {
                    toDelete.add(element.id)
                }
            }

            // Also find descendants by parent relationships
            val parentsToCheck = toDelete.toMutableSet()
            while (parentsToCheck.isNotEmpty()) {
                val currentParents = parentsToCheck.toSet()
                parentsToCheck.clear()

                contentElementStorage.values.forEach { element ->
                    // Check if this element has a parent in our set
                    val parentId = when (element) {
                        is Chunk -> element.parentId
                        is LeafSection -> element.parentId
                        is NavigableContainerSection -> element.parentId
                        else -> null
                    }

                    if (parentId != null && currentParents.contains(parentId) && !toDelete.contains(element.id)) {
                        toDelete.add(element.id)
                        parentsToCheck.add(element.id)
                    }
                }
            }

            logger.info("Found {} elements to delete for URI: {}", toDelete.size, uri)

            // Delete from Lucene index
            toDelete.forEach { id ->
                indexWriter.deleteDocuments(org.apache.lucene.index.Term(ID_FIELD, id))
            }

            // Delete from content storage
            toDelete.forEach { id ->
                contentElementStorage.remove(id)
            }

            // Commit changes
            commit()

            val result = DocumentDeletionResult(
                rootUri = uri,
                deletedCount = toDelete.size
            )

            logger.info("Deleted {} elements for document with URI: {}", toDelete.size, uri)
            return result
        }
    }

    override fun findContentRootByUri(uri: String): ContentRoot? {
        logger.debug("Finding root document with URI: {}", uri)
        return synchronized(this) {
            contentElementStorage.values.find {
                it.uri == uri && it.labels().any { label ->
                    label.contains("Document") || label.contains("ContentRoot")
                }
            } as? ContentRoot
        }
    }

    /**
     * Clear all stored content - useful for testing
     * @return Number of chunks cleared
     */
    fun clear(): Int {
        val count = contentElementStorage.size
        logger.info("Clearing all indexed content from Lucene RAG service")
        synchronized(this) {
            // Clear chunk storage
            contentElementStorage.clear()

            // Clear Lucene index
            indexWriter.deleteAll()
            indexWriter.commit()

            // Invalidate reader
            invalidateReader()
        }
        logger.info("Cleared {} chunks from Lucene RAG service", count)
        return count
    }

    override fun info(): LuceneStatistics {
        val docCount = try {
            refreshReaderIfNeeded()
            directoryReader?.numDocs() ?: 0
        } catch (_: Exception) {
            0
        }

        return LuceneStatistics(
            chunkCount = findAll(Chunk::class.java).size,
            contentElementCount = contentElementStorage.size,
            documentCount = docCount,
            averageChunkLength = if (contentElementStorage.isNotEmpty()) {
                contentElementStorage.values.filterIsInstance<Chunk>().map { it.text.length }.average()
            } else 0.0,
            hasEmbeddings = embeddingModel != null,
            vectorWeight = vectorWeight,
            isPersistent = indexPath != null,
            indexPath = indexPath?.toString()
        )
    }

}

/**
 * Statistics about the Lucene RAG service state
 */
data class LuceneStatistics(
    override val chunkCount: Int,
    override val documentCount: Int,
    override val contentElementCount: Int,
    val averageChunkLength: Double,
    override val hasEmbeddings: Boolean,
    val vectorWeight: Double,
    override val isPersistent: Boolean,
    val indexPath: String?,
) : ContentElementRepositoryInfo
