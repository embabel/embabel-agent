/*
 * Copyright 2024-2026 Embabel Pty Ltd.
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

import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.model.Chunk
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
     * Perform classic vector search.
     * @param request the search request containing query, topK, and similarity threshold
     * @param clazz the type of Retrievable to search for
     * @return matching results ranked by similarity score
     */
    fun <T : Retrievable> vectorSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>>
}

/**
 * Vector search with native property filtering support.
 * Implementations translate [PropertyFilter] to native query syntax
 * (e.g., Spring AI Filter.Expression, Cypher WHERE clause, Lucene field queries).
 */
interface FilteringVectorSearch : VectorSearch {

    /**
     * Perform vector search with property filtering.
     *
     * @param request the search request containing query, topK, and similarity threshold
     * @param clazz the type of Retrievable to search for
     * @param metadataFilter filter on metadata properties (e.g., source, ingestion date)
     * @param entityFilter filter on object properties (e.g., entity fields) and label
     * @return matching results ranked by similarity score
     */
    fun <T : Retrievable> vectorSearchWithFilter(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
        metadataFilter: PropertyFilter? = null,
        entityFilter: EntityFilter? = null,
    ): List<SimilarityResult<T>>
}

/**
 * How an implementation interprets the `query` string handed to [TextSearch.textSearch].
 *
 * The two are mutually exclusive by design: a surface that escapes *some* of its input is
 * predictable to nobody, human or model. An implementation declares what it can serve via
 * [TextSearch.supportedQueryModes], and which it is serving via [TextSearch.queryMode].
 */
enum class TextQueryMode {

    /**
     * The query is **literal text**. The implementation escapes it, so no character can be mistaken
     * for an operator and no input can fail to parse — a pasted URL, file path or code fragment is
     * searched for rather than interpreted.
     *
     * The caller cannot express required terms, exclusions or phrases, so any precision beyond
     * ranking has to come from the implementation. What that means varies: an engine-backed store
     * may escape the text and then ENHANCE it — requiring identifier-shaped tokens on the caller's
     * behalf — while a substring-matching store simply matches. Both are honest [LITERAL]; the mode
     * describes how the caller's text is read, not how hard the implementation works afterwards.
     *
     * Prefer this for callers that cannot reliably compose an expression, notably small models, for
     * which a malformed expression is a likelier outcome than a useful one.
     */
    LITERAL,

    /**
     * The query is a **Lucene expression** the caller composes, passed through unaltered. The caller
     * owns precision, and owns escaping: an unbalanced `/` or a bare `:` is a parse failure, not a
     * search for those characters.
     */
    EXPRESSION,
}

/**
 * Full-text search.
 *
 * How the `query` string is interpreted depends on [queryMode] — see [TextQueryMode]. Every
 * implementation historically behaved as [TextQueryMode.EXPRESSION], which remains the default.
 */
interface TextSearch : TypeRetrievalOperations {

    /**
     * The query modes this implementation can serve. Never empty.
     *
     * A capability, not a preference. An implementation backed by substring matching cannot honour
     * an expression however it is configured, and one backed by an engine whose syntax differs from
     * Lucene's should not claim [TextQueryMode.EXPRESSION] merely because it has a parser.
     * Deployments choose among these; they cannot choose outside them.
     */
    val supportedQueryModes: Set<TextQueryMode>
        get() = setOf(TextQueryMode.EXPRESSION)

    /**
     * The mode currently in effect. Must be a member of [supportedQueryModes].
     *
     * [luceneSyntaxNotes] must describe THIS mode. It reaches the model verbatim in the search
     * tool's description, so a store advertising expression syntax while escaping its input teaches
     * the model to write queries that cannot work.
     */
    val queryMode: TextQueryMode
        get() = supportedQueryModes.first()

    /**
     * Performs full-text search.
     *
     * The syntax below applies when [queryMode] is [TextQueryMode.EXPRESSION]. Under
     * [TextQueryMode.LITERAL] the query is escaped and matched as text, so none of these operators
     * has any effect.
     *
     * Not all implementations will support all capabilities (such as fuzzy matching).
     * However, the use of quotes for phrases and + / - for required / excluded terms should be
     * widely supported among implementations that declare [TextQueryMode.EXPRESSION].
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
     *
     * Default is empty — implementations are expected to override with the
     * actual supported syntax (e.g. `"Full Lucene syntax supported"`,
     * `"PostgreSQL substring matching only"`, `"Not supported"`). The
     * default exists so test mocks and minimal stubs don't have to
     * provide a value, and so [TextSearchTools] can compose its tool
     * description without crashing on un-stubbed mocks.
     */
    val luceneSyntaxNotes: String
        get() = ""
}

/**
 * Text search with native property filtering support.
 * Implementations translate [PropertyFilter] to native query syntax.
 */
interface FilteringTextSearch : TextSearch {

    /**
     * Perform text search with property filtering.
     *
     * @param request the text similarity search request
     * @param clazz the type of [Retrievable] to search
     * @param metadataFilter filter on metadata properties (e.g., source, ingestion date)
     * @param entityFilter filter on object properties (e.g., entity fields)
     * @return matching results ranked by BM25 relevance score
     */
    fun <T : Retrievable> textSearchWithFilter(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
        metadataFilter: PropertyFilter? = null,
        entityFilter: EntityFilter? = null,
    ): List<SimilarityResult<T>>
}

interface RegexSearchOperations : SearchOperations {

    /**
     * Perform regex search.
     * @param regex the regex pattern to match
     * @param topK maximum number of results to return
     * @param clazz the type of Retrievable to search for
     * @return matching results
     */
    fun <T : Retrievable> regexSearch(
        regex: Regex,
        topK: Int,
        clazz: Class<T>,
    ): List<SimilarityResult<T>>
}

/**
 * Regex search with native property filtering support.
 */
interface FilteringRegexSearch : RegexSearchOperations {

    /**
     * Perform regex search with property filtering.
     *
     * @param regex the regex pattern to match
     * @param topK maximum number of results to return
     * @param clazz the type of Retrievable to search for
     * @param metadataFilter filter on metadata properties (e.g., source, ingestion date)
     * @param entityFilter filter on object properties (e.g., entity fields) and labels
     * @return matching results
     */
    fun <T : Retrievable> regexSearchWithFilter(
        regex: Regex,
        topK: Int,
        clazz: Class<T>,
        metadataFilter: PropertyFilter? = null,
        entityFilter: EntityFilter? = null,
    ): List<SimilarityResult<T>>
}

/**
 * One section heading of an ingested document, as reported by [SectionReader.listSections].
 */
data class SectionSummary(
    val title: String,
    val documentTitle: String,
    val chunkCount: Int,
)

/**
 * Interface to be implemented by stores that can navigate a document's section
 * structure by NAME: list the section headings of a document (its table of
 * contents) and read every chunk of a named section in document order.
 *
 * Search finds isolated hits; some content is only usable whole. The motivating
 * case is structured data split across chunks — a financial statement whose
 * header row, row labels and values land in different chunks. A model that has
 * seen a section title (from [listSections], or from provenance embedded in a
 * retrieved chunk) can read the complete section in order instead of trying to
 * reassemble it from search results.
 *
 * Tenancy: implementations MUST scope results to their own tenant/context. The
 * tools built over this interface (unlike the vector/text search tools) apply
 * no metadata filter of their own, because section reads are keyed by title
 * rather than similarity and per-store scoping is where multi-tenant stores
 * already enforce isolation.
 */
interface SectionReader : SearchOperations {

    /**
     * The section headings of ingested documents, with chunk counts.
     * @param documentTitle case-insensitive substring filter on the document
     * title; null lists sections for all visible documents
     */
    fun listSections(documentTitle: String? = null): List<SectionSummary>

    /**
     * Every chunk of the named section(s), in document sequence order.
     * @param sectionTitle exact section title, matched case-insensitively
     * @param documentTitle optional case-insensitive substring filter on the
     * document title, to disambiguate when documents share section names
     */
    fun readSection(sectionTitle: String, documentTitle: String? = null): List<Chunk>
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
interface CoreSearchOperations : VectorSearch, TextSearch
