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
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.TopDocs
import org.apache.lucene.store.ByteBuffersDirectory
import java.io.Closeable
import org.springframework.ai.document.Document as SpringAiDocument

class LuceneRagService(
    override val name: String = "lucene-rag",
    override val description: String = "In-memory Lucene-based RAG service with vector search capabilities",
) : WritableRagService, Closeable {

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
            service = name,
            results = emptyList()
        )

        val searcher = IndexSearcher(reader)
        val query: Query = queryParser.parse(QueryParser.escape(ragRequest.query))

        val topDocs: TopDocs = searcher.search(query, ragRequest.topK)

        val results = topDocs.scoreDocs
            .filter { it.score >= ragRequest.similarityThreshold.toDouble() }
            .map { scoreDoc ->
                val doc = searcher.doc(scoreDoc.doc)
                val retrievable = DocumentRetrievable(
                    id = doc.get("id"),
                    content = doc.get("content"),
                    metadata = doc.fields
                        .filter { field -> field.name() !in setOf("id", "content") }
                        .associate { field -> field.name() to field.stringValue() }
                )
                SimpleSimilaritySearchResult(
                    match = retrievable,
                    score = scoreDoc.score.toDouble()
                )
            }
            .sortedByDescending { it.score }

        return RagResponse(
            service = name,
            results = results
        )
    }

    override fun accept(documents: List<SpringAiDocument>) {
        documents.forEach { springDoc ->
            val luceneDoc = Document().apply {
                add(StringField("id", springDoc.id, Field.Store.YES))
                add(TextField("content", springDoc.text, Field.Store.YES))

                springDoc.metadata.forEach { (key, value) ->
                    add(StringField(key, value.toString(), Field.Store.YES))
                }
            }

            indexWriter.addDocument(luceneDoc)
        }

        indexWriter.commit()
        invalidateReader()
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