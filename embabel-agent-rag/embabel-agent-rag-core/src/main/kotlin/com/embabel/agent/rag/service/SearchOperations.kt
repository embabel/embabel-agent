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
package com.embabel.agent.rag.service

import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest

/**
 * Tag interface for search operations
 * Concrete implementations are RAG building blocks that
 * implement one or more subinterfaces and
 * are easy to expose to LLMs via tools
 */
interface SearchOperations

/**
 * Supports listing supported retrievable types
 */
interface TypeRetrievalOperations : SearchOperations {

    /**
     * Is this type supported?
     * @param type the type name of the retrievable
     * Normally matches the simple class name of the retrievable type
     * or the name of a schema type
     * @return true if supported
     */
    fun supportsType(type: String): Boolean
}


/**
 * Supports retrieval of retrievables by ID
 */
interface FinderOperations : TypeRetrievalOperations {

    /**
     * Retrieve an entity by its ID
     * Core finder support not necessarily exposed as LLM tool.
     */
    fun <T> findById(
        id: String,
        clazz: Class<T>,
    ): T?

    /**
     * Retrieve an entity by its ID and type name
     * @param id the ID of the retrievable
     * @param type the type name of the retrievable
     * Normally matches the simple class name of the retrievable type
     * or the name of a schema type
     */
    fun <T : Retrievable> findById(
        id: String,
        type: String
    ): T?
}

/**
 * Traditional RAG vector search
 */
interface VectorSearch : TypeRetrievalOperations {

    /**
     * Perform classic vector search
     */
    fun <T : Retrievable> vectorSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>>

}

/**
 * Full-text search using Lucene query syntax
 */
interface TextSearch : SearchOperations {
    /**
     * Performs full-text search using Lucene query syntax.
     * Not all implementations will support all capabilities (such as fuzzy matching).
     * However, the use of quotes for phrases and + / - for required / excluded terms should be widely supported.
     *
     * The "query" field of request supports the following syntax:
     *
     * ## Basic queries
     * - `machine learning` - matches documents containing either term (implicit OR)
     * - `+machine +learning` - both terms required (AND)
     * - `"machine learning"` - exact phrase match
     *
     * ## Modifiers
     * - `+term` - term must appear
     * - `-term` - term must not appear
     * - `term*` - prefix wildcard
     * - `term~` - fuzzy match (edit distance)
     * - `term~0.8` - fuzzy match with similarity threshold
     *
     * ## Query Field Examples
     * ```
     * // Find chunks mentioning either kotlin or java
     * "kotlin java"
     *
     * // Find chunks with both "error" and "handling"
     * "+error +handling"
     *
     * // Find exact phrase
     * "\"null pointer exception\""
     *
     * // Find "test" but exclude "unit"
     * "+test -unit"
     * ```
     *
     * @param request the text similarity search request
     * @param clazz the type of [Retrievable] to search
     * @return matching results ranked by BM25 relevance score
     */
    fun <T : Retrievable> textSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>>

    /**
     * Notes on how much Lucene syntax is supported by this implementation
     * to help LLMs and users craft effective queries.
     */
    val luceneSyntaxNotes: String
}

interface RegexSearchOperations : SearchOperations {

    fun <T : Retrievable> regexSearch(
        regex: Regex,
        topK: Int,
        clazz: Class<T>,
    ): List<SimilarityResult<T>>
}

/**
 * Interface to be implemented by stores that can expand search results
 * to find earlier and later content elements in sequence, or enclosing sections.
 * Works with HierarchicalContentElement types and supporting stores.
 */
interface ResultExpander : SearchOperations {

    enum class Method {
        /** Expand to previous and next chunks in sequence */
        SEQUENCE,

        /** Expand to enclosing section */
        ZOOM_OUT,
    }

    /**
     * Expand the given ContentElement by finding related ContentElements.
     * Could be based on vector similarity, text similarity, or other relationships.
     * @param id the ID of the chunk to expand
     * @param method the expansion method to use
     * @param elementsToAdd number of elements to add
     * @return list of related elements
     */
    fun expandResult(
        id: String,
        method: Method,
        elementsToAdd: Int,
    ): List<ContentElement>
}

/**
 * Commonly implemented set of search functionality
 */
interface CoreSearchOperations : FinderOperations, VectorSearch, TextSearch
