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
import com.embabel.agent.rag.ingestion.ContentElementRepository
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.util.indent
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.*
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.TopDocs
import org.apache.lucene.store.ByteBuffersDirectory
import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt
import org.springframework.ai.document.Document as SpringAiDocument

/**
 * LuceneRagService with optional vector search support via an EmbeddingModel.
 * Works in memory. Be careful if loading excessive content!
 */
class LuceneRagService @JvmOverloads constructor(
    override val name: String,
    override val description: String,
    private val embeddingModel: EmbeddingModel? = null,
    private val vectorWeight: Double = 0.5, // Balance between text and vector similarity
) : WritableRagService, ContentElementRepository, Closeable {

    private val logger = LoggerFactory.getLogger(LuceneRagService::class.java)

    init {

        if (embeddingModel == null) {
            logger.warn("No embedding model configured; only text search will be supported.")
        }
    }

    private val analyzer = StandardAnalyzer()
    private val directory = ByteBuffersDirectory()
    private val indexWriterConfig = IndexWriterConfig(analyzer)
    private val indexWriter = IndexWriter(directory, indexWriterConfig)
    private val queryParser = QueryParser("content", analyzer)

    @Volatile
    private var directoryReader: DirectoryReader? = null

    private val contentElementStorage = ConcurrentHashMap<String, ContentElement>()

    override fun findChunksById(chunkIds: List<String>): List<Chunk> {
        logger.debug("Finding chunks by IDs: {}", chunkIds)

        val foundChunks = chunkIds.mapNotNull { chunkId ->
            contentElementStorage[chunkId] as? Chunk
        }

        logger.debug("Found {}/{} chunks", foundChunks.size, chunkIds.size)
        return foundChunks
    }

    override fun findById(id: String): ContentElement? {
        return contentElementStorage[id]
    }

    override fun save(element: ContentElement): ContentElement {
        contentElementStorage[element.id] = element
        return element
    }

    fun findAll(): List<Chunk> {
        logger.debug("Retrieving all chunks from storage")
        val allChunks = contentElementStorage.values.filterIsInstance<Chunk>()
        logger.debug("Retrieved {} chunks from storage", allChunks.size)
        return allChunks
    }

    override fun search(ragRequest: RagRequest): RagResponse {
        refreshReaderIfNeeded()

        val reader = directoryReader ?: return RagResponse(
            request = ragRequest,
            service = name,
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

        return RagResponse(
            request = ragRequest,
            service = name,
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
        return Chunk(
            id = doc.get("id"),
            text = doc.get("content"),
            metadata = doc.fields
                .filter { field -> field.name() !in setOf("id", "content", "embedding") }
                .associate { field -> field.name() to field.stringValue() }
        )
    }

    override fun writeContent(root: MaterializedContentRoot): List<String> {
        val chunker = ContentChunker()
        val chunks = chunker.chunk(root)
        save(root)
        root.descendants().forEach { save(it) }
        chunks.forEach { chunk -> writeChunk(chunk, chunk.metadata) }
        commit()
        return chunks.map { it.id }
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
            writeChunk(chunk, springDoc.metadata)
        }
        commit()
        logger.info(
            "Successfully indexed {} documents. Total chunks in storage: {}",
            documents.size, contentElementStorage.size
        )
    }

    private fun writeChunk(
        chunk: Chunk,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        contentElementStorage[chunk.id] = chunk
        // Create Lucene document for indexing
        val luceneDoc = Document().apply {
            add(StringField("id", chunk.id, Field.Store.YES))
            add(TextField("content", chunk.text, Field.Store.YES))

            if (embeddingModel != null) {
                val embedding = embeddingModel.embed(chunk.text)
                val embeddingBytes = floatArrayToBytes(embedding)
                add(StoredField("embedding", embeddingBytes))
            }

            metadata.forEach { (key, value) ->
                add(StringField(key, value.toString(), Field.Store.YES))
            }
        }
        indexWriter.addDocument(luceneDoc)
        logger.debug("Indexed and stored chunk with id='{}' and text length={}", chunk.id, chunk.text.length)
    }

    private fun commit() {
        indexWriter.commit()
        invalidateReader()
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

    private fun refreshReaderIfNeeded() {
        synchronized(this) {
            if (directoryReader == null) {
                try {
                    directoryReader = DirectoryReader.open(directory)
                } catch (e: Exception) {
                    // Index might be empty, which is fine
                }
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
        } catch (e: Exception) {
            0
        }

        val chunkCount = contentElementStorage.size
        val basicInfo = "LuceneRagService: $name ($docCount documents, $chunkCount chunks)"

        return if (verbose == true) {
            val embeddingInfo = if (embeddingModel != null) "with embeddings" else "text-only"
            val vectorWeightInfo = if (embeddingModel != null) ", vector weight: $vectorWeight" else ""
            "$basicInfo ($embeddingInfo$vectorWeightInfo)".indent(indent)
        } else {
            basicInfo.indent(indent)
        }
    }

    override fun close() {
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
        } catch (e: Exception) {
            0
        }

        return LuceneStatistics(
            totalChunks = contentElementStorage.size,
            totalDocuments = docCount,
            averageChunkLength = if (contentElementStorage.isNotEmpty()) {
                contentElementStorage.values.filterIsInstance<Chunk>().map { it.text.length }.average()
            } else 0.0,
            hasEmbeddings = embeddingModel != null,
            vectorWeight = vectorWeight
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
)
