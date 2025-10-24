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
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.reader.TextReader
import org.springframework.ai.transformer.splitter.TextSplitter
import org.springframework.ai.transformer.splitter.TokenTextSplitter

/**
 * Write to all RAG services that implement [com.embabel.agent.rag.WritableStore].
 * Users can override the [org.springframework.ai.transformer.splitter.TextSplitter] to control how text is split into documents.
 */
class MultiIngester(
    override val stores: List<WritableStore>,
    private val splitterProvider: () -> TextSplitter = { TokenTextSplitter() },
) : Ingester {

    private val splitter: TextSplitter by lazy { splitterProvider() }

    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        logger.info(
            "{} with {} writable rag services: Using text splitter {}",
            javaClass.simpleName,
            stores.size,
            splitter,
        )
    }

    override fun active(): Boolean = stores.isNotEmpty()

    override fun ingest(resourcePath: String): IngestionResult {
        val sourceDocs = TextReader(resourcePath).get()
        val documents = splitter.split(sourceDocs)
        logger.info(
            "Split {} source documents at {} into {} indexable chunks: Will write to {} writable rag services",
            sourceDocs.size, resourcePath, documents.size, stores.size
        )
        logger.debug("Documents: {}", documents.joinToString("\n"))
        return writeToStores(documents)
    }

    override fun accept(documents: List<Document>) {
        writeToStores(documents)
    }

    private fun writeToStores(documents: List<Document>): IngestionResult {
        val storesWrittenTo = stores
            .map {
                it.write(documents)
                it.name
            }
        return IngestionResult(
            chunkIds = documents.map { it.id },
            storesWrittenTo = storesWrittenTo.toSet(),
        )
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        if (stores.isEmpty()) "No RAG services" else
            "${javaClass.simpleName} of ${
                stores.joinToString(",") {
                    it.name
                }
            }"
}
