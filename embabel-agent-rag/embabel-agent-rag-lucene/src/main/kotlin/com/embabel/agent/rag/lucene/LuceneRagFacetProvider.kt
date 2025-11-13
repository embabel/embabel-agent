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

import com.embabel.agent.rag.*
import com.embabel.agent.rag.ingestion.ContentChunker
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.CONTAINER_SECTION_ID
import com.embabel.agent.rag.ingestion.ContentChunker.Companion.SEQUENCE_NUMBER
import com.embabel.agent.rag.ingestion.MaterializedDocument
import com.embabel.agent.rag.support.FunctionRagFacet
import com.embabel.agent.rag.support.RagFacet
import com.embabel.agent.rag.support.RagFacetProvider
import com.embabel.agent.rag.support.RagFacetResults
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.util.indent
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
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt
import org.springframework.ai.document.Document as SpringAiDocument

/**
 * Lucene RAG facet with optional vector search support via an EmbeddingModel.
 * Supports both in-memory and disk-based persistence.
 * Implements WritableContentElementRepository so we can add to the store.
 *
 * @param name Name of this RAG service
 * @param embeddingModel Optional embedding model for vector search; if null, only text search is
 * supported
 * @param vectorWeight Weighting for vector similarity in hybrid search (0.0 to 1.0)
 * @param chunkerConfig Configuration for content chunking
 * @param indexPath Optional path for disk-based index storage; if null, uses in-memory storage
 */
class LuceneRagFacetProvider @JvmOverloads constructor(
    override val name: String,
    override val enhancers: List<RetrievableEnhancer> = emptyList(),
    private val embeddingModel: EmbeddingModel? = null,
    private val vectorWeight: Double = 0.5,
    chunkerConfig: ContentChunker.Config = ContentChunker.DefaultConfig(),
    private val indexPath: Path? = null,
) : RagFacetProvider, AbstractWritableContentElementRepository(chunkerConfig), HasInfoString, Closeable {

    private val logger = LoggerFactory.getLogger(LuceneRagFacetProvider::class.java)

    private val analyzer = StandardAnalyzer()
    private val directory: Directory = indexPath?.let { FSDirectory.open(it) } ?: ByteBuffersDirectory()
    private val indexWriterConfig = IndexWriterConfig(analyzer)
    private var indexWriter = IndexWriter(directory, indexWriterConfig)
    private val queryParser = QueryParser("content", analyzer)

    companion object {
        const val KEYWORDS_FIELD = "keywords"
        private const val CONTENT_FIELD = "content"
        private const val ID_FIELD = "id"
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
    fun loadExistingChunksFromDisk() {
        logger.info("Manually triggering chunk loading from disk...")
        ensureChunksLoaded()
    }

    @Volatile
    private var directoryReader: DirectoryReader? = null

    private val contentElementStorage = ConcurrentHashMap<String, ContentElement>()

    override fun facets(): List<RagFacet<out Retrievable>> {
        return listOf(
            FunctionRagFacet(
                name = "chunks",
                searchFunction = ::search,
            )
        )
    }

    override fun findChunksById(chunkIds: List<String>): List<Chunk> {
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

    override fun count(): Int =
        contentElementStorage.size


    override fun findById(id: String): ContentElement? {
        return contentElementStorage[id]
    }

    override fun save(element: ContentElement): ContentElement {
        contentElementStorage[element.id] = element
        return element
    }

    override fun createRelationships(root: MaterializedDocument) {
        // No op here
    }

    fun findAll(): List<Chunk> {
        ensureChunksLoaded()
        logger.debug("Retrieving all chunks from storage")
        val allChunks = contentElementStorage.values
            .filterIsInstance<Chunk>()
            .sortedBy { "${it.metadata[CONTAINER_SECTION_ID]}-${it.metadata[SEQUENCE_NUMBER]}" }
        logger.debug("Retrieved {} chunks from storage", allChunks.size)
        return allChunks
    }

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
     * @param keywords Set of keywords to search for
     * @param minIntersection Minimum number of keywords that must match (default: 1)
     * @param maxResults Maximum number of results to return (default: 100)
     * @return List of (chunkId, matchCount) pairs sorted by match count descending
     */
    fun findChunkIdsByKeywords(
        keywords: Set<String>,
        minIntersection: Int = 1,
        maxResults: Int = 100,
    ): List<Pair<String, Int>> {
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


    fun search(ragRequest: RagRequest): RagFacetResults<Chunk> {
        ensureChunksLoaded()
        refreshReaderIfNeeded()

        val reader = directoryReader ?: return RagFacetResults(
            facetName = name,
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

        val filteredResults = results
            .filter { it.score >= ragRequest.similarityThreshold }
            .take(ragRequest.topK)
            .sortedByDescending { it.score }

        return RagFacetResults(
            facetName = name,
            results = filteredResults
        )
    }

    private fun performTextSearch(
        searcher: IndexSearcher,
        ragRequest: RagRequest,
    ): List<SimpleSimilaritySearchResult<Chunk>> {
        val query: Query = queryParser.parse(QueryParser.escape(ragRequest.query))
        val topDocs: TopDocs = searcher.search(query, ragRequest.topK)

        return topDocs.scoreDocs.map { scoreDoc ->
            val doc = searcher.doc(scoreDoc.doc)
            val retrievable = createChunkFromDocument(doc)
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
            val retrievable = createChunkFromDocument(doc)

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

    private fun createChunkFromDocument(doc: Document): Chunk {
        val keywords = doc.getValues(KEYWORDS_FIELD)?.toList() ?: emptyList()

        val metadata = doc.fields
            .filter { field -> field.name() !in setOf(ID_FIELD, CONTENT_FIELD, "embedding", KEYWORDS_FIELD) }
            .associate { field -> field.name() to field.stringValue() as Any? }
            .toMutableMap()

        // Add keywords to metadata if present
        if (keywords.isNotEmpty()) {
            metadata[KEYWORDS_FIELD] = keywords as Any?
        }

        return Chunk(
            id = doc.get(ID_FIELD),
            text = doc.get(CONTENT_FIELD),
            metadata = metadata
        )
    }


    override fun accept(documents: List<SpringAiDocument>) {
        logger.info("Indexing {} documents into Lucene RAG service and storing as chunks", documents.size)
        documents.forEach { springDoc ->
            // Create and store chunk in memory
            val chunk = Chunk(
                id = springDoc.id,
                text = springDoc.text ?: "",
                metadata = springDoc.metadata + mapOf(
                    "indexed_at" to System.currentTimeMillis(),
                    "service" to name
                )
            )
            onNewRetrievable(chunk)
        }
        commit()
        logger.info(
            "Successfully indexed {} documents. Total chunks in storage: {}",
            documents.size, contentElementStorage.size
        )
    }

    override fun onNewRetrievables(retrievables: List<Retrievable>) {
        retrievables.forEach { onNewRetrievable(it) }
    }

    private fun onNewRetrievable(
        retrievable: Retrievable,
    ) {
        if (retrievable !is Chunk) {
            logger.warn("Only Chunk retrievables are supported; skipping retrievable with id='{}'", retrievable.id)
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
                val embedding = embeddingModel.embed(retrievable.embeddableValue())
                val embeddingBytes = floatArrayToBytes(embedding)
                add(StoredField("embedding", embeddingBytes))
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
                        logger.debug(
                            "Loading document {}: id={}, content preview={}",
                            i,
                            doc.get("id"),
                            doc.get("content")?.take(50)
                        )
                        val chunk = createChunkFromDocument(doc)
                        contentElementStorage[chunk.id] = chunk
                        logger.info(
                            "✅ Loaded chunk with id={} and keywords {}",
                            chunk.id,
                            chunk.metadata.get(KEYWORDS_FIELD),
                        )
                    } catch (e: Exception) {
                        logger.error("❌ Failed to load document {}: {}", i, e.message, e)
                    }
                }

                reader.close()
                logger.info(
                    "✅ Loaded {} existing chunks from disk index {}",
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

    /**
     * Clear all stored content - useful for testing
     */
    fun clear() {
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
    }

    /**
     * Get statistics about the current state
     */
    fun getStatistics(): LuceneStatistics {
        val docCount = try {
            refreshReaderIfNeeded()
            directoryReader?.numDocs() ?: 0
        } catch (_: Exception) {
            0
        }

        return LuceneStatistics(
            totalChunks = contentElementStorage.size,
            totalDocuments = docCount,
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
    val totalChunks: Int,
    val totalDocuments: Int,
    val averageChunkLength: Double,
    val hasEmbeddings: Boolean,
    val vectorWeight: Double,
    val isPersistent: Boolean,
    val indexPath: String?,
)
