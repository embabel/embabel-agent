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
package com.embabel.agent.rag.support

import com.embabel.agent.rag.Ingester
import com.embabel.agent.rag.IngestionResult
import com.embabel.agent.rag.WritableRagService
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.reader.TextReader
import org.springframework.ai.transformer.splitter.TextSplitter
import org.springframework.ai.transformer.splitter.TokenTextSplitter


/**
 * Write to all RAG services that implement [WritableRagService].
 * Users can override the [TextSplitter] to control how text is split into documents.
 */
class MultiIngester(
    override val ragServices: List<WritableRagService>,
    val splitter: TextSplitter = TokenTextSplitter(),
) : Ingester {

    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        logger.info("Using text splitter {}", splitter)
    }

    override fun active(): Boolean = ragServices.isNotEmpty()

    override fun ingest(resourcePath: String): IngestionResult {
        val sourceDocs = TextReader(resourcePath).get()
        val documents = splitter.split(sourceDocs)
        logger.info("Split {} into {} documents from {}", sourceDocs.size, documents.size, resourcePath)
        return writeToStores(documents)
    }

    override fun accept(documents: List<Document>) {
        writeToStores(documents)
    }

    private fun writeToStores(documents: List<Document>): IngestionResult {
        val storesWrittenTo = ragServices
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
        if (ragServices.isEmpty()) "No RAG services" else
            "Multi ingester of ${
                ragServices.joinToString(",") {
                    it.infoString(verbose = verbose, indent = 1)
                }
            }"
}
