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

import com.embabel.agent.rag.RagRequest
import com.embabel.agent.rag.RagResponse
import com.embabel.agent.rag.Retrievable
import com.embabel.agent.rag.WritableRagService
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
import kotlin.math.sqrt
import org.springframework.ai.document.Document as SpringAiDocument

class LuceneRagService @JvmOverloads constructor(
    override val name: String = "lucene-rag",
    override val description: String = "In-memory Lucene-based RAG service with hybrid text and vector search capabilities",
    private val embeddingModel: EmbeddingModel? = null,
    private val vectorWeight: Double = 0.5, // Balance between text and vector similarity
) : WritableRagService, Closeable {

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


    override fun search(ragRequest: RagRequest): RagResponse {
        refreshReaderIfNeeded()

        val reader = directoryReader ?: return RagResponse(
            request = ragRequest,
            service = name,
            results = emptyList()
        )

        val searcher = IndexSearcher(reader)

        // Perform hybrid search: text + vector similarity
        val hybridResults = if (embeddingModel != null) {
            performHybridSearch(searcher, ragRequest)
        } else {
            performTextSearch(searcher, ragRequest)
        }

        val filteredResults = hybridResults
            .filter { it.score >= ragRequest.similarityThreshold.toDouble() }
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
    ): List<SimpleSimilaritySearchResult<DocumentRetrievable>> {
        val query: Query = queryParser.parse(QueryParser.escape(ragRequest.query))
        val topDocs: TopDocs = searcher.search(query, ragRequest.topK)

        return topDocs.scoreDocs.map { scoreDoc ->
            val doc = searcher.doc(scoreDoc.doc)
            val retrievable = createRetrievableFromDocument(doc)
            SimpleSimilaritySearchResult(
                match = retrievable,
                score = scoreDoc.score.toDouble()
            )
        }
    }

    private fun performHybridSearch(
        searcher: IndexSearcher,
        ragRequest: RagRequest,
    ): List<SimpleSimilaritySearchResult<DocumentRetrievable>> {
        val textQuery: Query = queryParser.parse(QueryParser.escape(ragRequest.query))
        val textResults: TopDocs = searcher.search(textQuery, Math.max(ragRequest.topK * 2, 20))

        // Get query embedding
        val queryEmbedding = embeddingModel!!.embed(ragRequest.query)

        // Calculate hybrid scores
        val hybridResults = mutableListOf<SimpleSimilaritySearchResult<DocumentRetrievable>>()

        for (scoreDoc in textResults.scoreDocs) {
            val doc = searcher.doc(scoreDoc.doc)
            val retrievable = createRetrievableFromDocument(doc)

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

    private fun createRetrievableFromDocument(doc: Document): DocumentRetrievable {
        return DocumentRetrievable(
            id = doc.get("id"),
            content = doc.get("content"),
            metadata = doc.fields
                .filter { field -> field.name() !in setOf("id", "content", "embedding") }
                .associate { field -> field.name() to field.stringValue() }
        )
    }

    override fun accept(documents: List<SpringAiDocument>) {
        documents.forEach { springDoc ->
            val luceneDoc = Document().apply {
                add(StringField("id", springDoc.id, Field.Store.YES))
                add(TextField("content", springDoc.text, Field.Store.YES))

                if (embeddingModel != null) {
                    val embedding = embeddingModel.embed(springDoc.text!!)
                    val embeddingBytes = floatArrayToBytes(embedding)
                    add(StoredField("embedding", embeddingBytes))
                }

                springDoc.metadata.forEach { (key, value) ->
                    add(StringField(key, value.toString(), Field.Store.YES))
                }
            }

            indexWriter.addDocument(luceneDoc)
        }

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

        return "LuceneRagService: $name (${docCount} documents)".indent(indent)
    }

    override fun close() {
        directoryReader?.close()
        indexWriter.close()
        directory.close()
        analyzer.close()
    }
}

private data class DocumentRetrievable(
    override val id: String,
    val content: String,
    override val metadata: Map<String, Any?>,
) : Retrievable {

    override fun embeddableValue(): String = content

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "Document[$id]: ${content.take(100)}${if (content.length > 100) "..." else ""}".indent(indent)
    }
}
