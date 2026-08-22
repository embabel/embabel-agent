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
package com.embabel.agent.rag.tools

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.filter.PropertyFilter
import tools.jackson.module.kotlin.jacksonObjectMapper
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.Embeddable
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.*
import com.embabel.agent.rag.service.support.Bm25Normalization
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.common.util.loggerFor
import com.embabel.common.util.time
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Classic vector search
 */
/**
 * Neighbouring chunks around each hit, folded into the result set.
 *
 * Reuses the same [ResultExpander] seam `broadenChunk` exposes to the model, so a store that
 * cannot expand simply never expands — no capability check to keep in step.
 *
 * A neighbour INHERITS its hit's score rather than being scored itself. It is continuation
 * text, not a rival candidate: scoring it independently would let context outrank the match
 * that found it, and reordering results is not what expansion is for.
 *
 * Deduplicated by id and appended after the hits, so an expanded set is a superset of the
 * unexpanded one in the same order. That matters for anything reading these results
 * positionally, and it means turning the knob on cannot lose a result.
 */
internal fun List<SimilarityResult<out Retrievable>>.withNeighbours(
    expander: ResultExpander?,
    each: Int,
): List<SimilarityResult<out Retrievable>> {
    if (expander == null || each <= 0 || isEmpty()) return this
    val seen = mapTo(mutableSetOf()) { it.match.id }
    val extra = flatMap { hit ->
        runCatching { expander.expandResult(hit.match.id, ResultExpander.Method.SEQUENCE, each) }
            .getOrElse { e ->
                LoggerFactory.getLogger("com.embabel.agent.rag.tools.expand")
                    .warn("Could not expand {}: {}", hit.match.id, e.message)
                emptyList<com.embabel.agent.rag.model.ContentElement>()
            }
            .filterIsInstance<Chunk>()
            .filter { neighbour -> seen.add(neighbour.id) }
            .map { neighbour ->
                object : SimilarityResult<Chunk> {
                    override val match: Chunk = neighbour
                    override val score: Double = hit.score
                }
            }
    }
    return this + extra
}

@Suppress("UNCHECKED_CAST")
internal fun <T : Retrievable> VectorSearch.vectorSearchWithFilter(
    request: TextSimilaritySearchRequest,
    clazz: Class<T>,
    metadataFilter: PropertyFilter?,
    entityFilter: EntityFilter?,
): List<SimilarityResult<T>> = when {
    metadataFilter == null && entityFilter == null -> vectorSearch(request, clazz)
    this is FilteringVectorSearch -> vectorSearchWithFilter(request, clazz, metadataFilter, entityFilter)
    else -> PostFilteringSearch.search(
        request,
        metadataFilter,
        entityFilter,
        TopKInflationStrategy.DEFAULT,
    ) { inflatedRequest ->
        vectorSearch(inflatedRequest, clazz)
    } as List<SimilarityResult<T>>
}

internal class VectorSearchTools @JvmOverloads constructor(
    private val vectorSearch: VectorSearch,
    private val searchFor: List<Class<out Retrievable>> = listOf(Chunk::class.java),
    private val metadataFilter: PropertyFilter? = null,
    private val entityFilter: EntityFilter? = null,
    private val resultsListener: ResultsListener? = null,
    private val searchDefaults: SearchDefaults = SearchDefaults.DEFAULT,
    private val resultExpander: ResultExpander? = null,
) : SearchTools {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    // `threshold` is OPTIONAL on purpose — see [SearchDefaults]. Models routinely
    // guessed a high cutoff (0.7-0.8), got nothing back, and concluded the corpus
    // didn't cover the question. topK is the limiter; the threshold is a noise floor.
    @LlmTool(description = "Perform vector search. Specify topK. Ranking handles relevance — omit threshold unless you have a specific reason to floor it.")
    fun vectorSearch(
        query: String,
        topK: Int,
        @LlmTool.Param(
            description = "Optional similarity floor from 0-1. Omit this unless you need to exclude weak matches: scores are not calibrated across embedding models, and setting it too high silently returns nothing.",
            required = false,
        ) threshold: ZeroToOne = searchDefaults.vectorSimilarityThreshold,
    ): String {
        logger.info(
            "Performing vector search with query='{}', topK={}, threshold={}, types={}, metadataFilter={}, entityFilter={}",
            query, topK, threshold, searchFor.map { it.simpleName }, metadataFilter, entityFilter
        )
        val request = TextSimilaritySearchRequest(query, threshold, topK)
        val results = search(request)
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.fromList<Retrievable>(results))
    }

    internal fun search(request: TextSimilaritySearchRequest): List<SimilarityResult<out Retrievable>> {
        val (hits, ms) = time {
            searchForAllTypes(request)
        }
        // Expanded BEFORE the listener fires: an observer must see what the model sees, or
        // reported provenance and the answer's actual evidence drift apart.
        val results = hits.withNeighbours(resultExpander, searchDefaults.expandNeighbours)
        resultsListener?.onResultsEvent(ResultsEvent(this, request.query, results, Duration.ofMillis(ms)))
        return results
    }

    private fun searchForAllTypes(request: TextSimilaritySearchRequest): List<SimilarityResult<out Retrievable>> {
        val allResults = searchFor.flatMap { clazz ->
            vectorSearch.vectorSearchWithFilter(request, clazz, metadataFilter, entityFilter)
        }
        return deduplicateByIdKeepingHighestScore(allResults)
    }
}

/**
 * Tools to expand the context around a chunk that has already been retrieved via search.
 *
 * Delegates to a [ResultExpander] implementation, which determines how chunks are
 * related and ordered. Provides two expansion strategies:
 * - **Broaden**: retrieves adjacent sibling chunks, giving more context
 *   from the same level of the content hierarchy (e.g., the paragraphs before and after).
 * - **Zoom out**: navigates up the content hierarchy to the parent section that contains
 *   the chunk. This is useful when a chunk matches a query but lacks the broader context
 *   needed to fully answer it — for example, a chunk mentioning a term defined in the
 *   enclosing section heading.
 *
 * Existing implementations (e.g., `embabel-agent-rag-graph`) apply sequence order
 * based on the graph structure of ingested content.
 */
internal class ResultExpanderTools @JvmOverloads constructor(
    private val resultExpander: ResultExpander,
    private val maxZoomOutChars: Int = DEFAULT_MAX_ZOOM_OUT_CHARS,
    private val resultsListener: ResultsListener? = null,
) : SearchTools {

    @LlmTool(description = "given a chunk ID, expand to surrounding chunks")
    fun broadenChunk(
        @LlmTool.Param(description = "id of the chunk to expand") chunkId: String,
        @LlmTool.Param(description = "chunksToAdd", required = false) chunksToAdd: Int = 2,
    ): String {
        val (chunks, ms) = time {
            resultExpander.expandResult(chunkId, ResultExpander.Method.SEQUENCE, chunksToAdd)
                .filterIsInstance<Chunk>()
        }
        if (chunks.isEmpty()) return "No adjacent chunks found for this section."
        // Expanded chunks are evidence the model SEES and quotes from. An observer
        // (attribution rollup, quoted-figure verification) that never learns of them
        // mislabels legitimate quotes as inventions — measured 2026-08-13, where a
        // fidelity check flagged clause numbers quoted from a broadened table of
        // contents. Same full-score contract as section reads: the model asked for
        // these chunks; every one is evidence.
        resultsListener?.onResultsEvent(
            ResultsEvent(
                this,
                "broadenChunk: $chunkId",
                chunks.map { SimpleSimilaritySearchResult<Retrievable>(it, 1.0) },
                Duration.ofMillis(ms),
            ),
        )
        return chunks.joinToString("\n") { "Chunk ID: ${it.id}\nContent: ${it.text}\n" }
    }

    @LlmTool(description = "given a content element ID, expand to parent section. If the result is too large, use vectorSearch or textSearch with a more specific query instead.")
    fun zoomOut(
        @LlmTool.Param(description = "id of the content element to expand") id: String,
    ): String {
        val (embeddables, ms) = time {
            resultExpander.expandResult(id, ResultExpander.Method.ZOOM_OUT, 1)
                .filter { it is Embeddable }
        }
        if (embeddables.isEmpty()) return "No parent section found."
        // Same observability contract as broadenChunk for whatever is Retrievable.
        val retrievables = embeddables.filterIsInstance<Retrievable>()
        if (retrievables.isNotEmpty()) {
            resultsListener?.onResultsEvent(
                ResultsEvent(
                    this,
                    "zoomOut: $id",
                    retrievables.map { SimpleSimilaritySearchResult<Retrievable>(it, 1.0) },
                    Duration.ofMillis(ms),
                ),
            )
        }
        val result = embeddables.joinToString("\n") { contentElement ->
            "${contentElement.javaClass.simpleName}: id=${contentElement.id}\nContent: ${(contentElement as Embeddable).embeddableValue()}\n"
        }
        if (result.length > maxZoomOutChars) {
            val truncated = result.take(maxZoomOutChars)
            return "$truncated\n\n[TRUNCATED — parent section is too large (${result.length} chars). " +
                "Use vectorSearch or textSearch with a more specific query to find the information you need, " +
                "or use broadenChunk to see adjacent chunks instead.]"
        }
        return result
    }

    companion object {
        const val DEFAULT_MAX_ZOOM_OUT_CHARS = 25_000
    }
}

/**
 * Tools to navigate a document's section structure by NAME, over a [SectionReader] store.
 *
 * Search retrieves isolated hits; some content only answers whole. The motivating case is
 * structured data split across chunks — a financial statement whose header row, row labels
 * and values land in different chunks, so search-hit reassembly picks wrong rows or a wrong
 * period column. `listSections` is the document's real table of contents; `readSection`
 * returns a named section complete and in order.
 *
 * Chunks returned by `readSection` are reported to the [ResultsListener] at full score, so
 * observed-attribution consumers see section reads the same way they see search hits.
 */
internal class SectionReadingTools @JvmOverloads constructor(
    private val sectionReader: SectionReader,
    private val resultsListener: ResultsListener? = null,
    private val maxReadSectionChars: Int = DEFAULT_MAX_READ_SECTION_CHARS,
) : SearchTools {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    @LlmTool(
        description = "List the section headings of a document — its table of contents — with chunk counts. " +
            "Use to find the exact section that holds structured data (a financial statement, a schedule, an appendix) " +
            "before reading it with readSection."
    )
    fun listSections(
        @LlmTool.Param(
            description = "Case-insensitive substring of the document title to scope to. Omit to list all documents' sections.",
            required = false,
        ) documentTitle: String? = null,
    ): String {
        val sections = sectionReader.listSections(documentTitle)
        if (sections.isEmpty()) {
            return "No sections found" + (documentTitle?.let { " for document title containing '$it'" } ?: "") + "."
        }
        val listing = sections
            .groupBy { it.documentTitle }
            .entries
            .joinToString("\n") { (doc, docSections) ->
                "Document: $doc\n" + docSections.joinToString("\n") { "  - ${it.title} (${it.chunkCount} chunks)" }
            }
        if (listing.length > maxReadSectionChars) {
            return listing.take(maxReadSectionChars) +
                "\n\n[TRUNCATED — narrow with the documentTitle parameter.]"
        }
        return listing
    }

    @LlmTool(
        description = "Read EVERY chunk of a named section in document order — e.g. a complete financial statement " +
            "or schedule. Use when you need structured data whole (all rows of a table, with the header row) rather " +
            "than isolated search hits. Get the exact title from listSections or from a retrieved chunk's Section header."
    )
    fun readSection(
        @LlmTool.Param(description = "Exact section title, as shown by listSections or a chunk's Section header")
        sectionTitle: String,
        @LlmTool.Param(
            description = "Case-insensitive substring of the document title, to disambiguate when documents share section names.",
            required = false,
        ) documentTitle: String? = null,
    ): String {
        logger.info("Reading section '{}' documentTitle={}", sectionTitle, documentTitle)
        val (chunks, ms) = time {
            sectionReader.readSection(sectionTitle, documentTitle)
        }
        if (chunks.isEmpty()) {
            return "No section titled '$sectionTitle' found" +
                (documentTitle?.let { " in a document whose title contains '$it'" } ?: "") +
                ". Use listSections to see the available section titles."
        }
        // NEVER blend documents. Similar documents share section names (contracts,
        // periodic filings), and a title match that silently concatenates several
        // documents hands the model the WRONG one to quote from — measured 2026-08-14
        // (CUAD): governing-law answers cited a different contract's clause at full
        // confidence. Ambiguity is surfaced as a choice, not merged.
        //
        // Provenance comes from the chunk's structural fields, which the chunker always
        // populates: keying off a free-form metadata key nobody writes would leave every
        // chunk indistinguishable and silently re-enable the blending this prevents.
        val documents = chunks
            .map { DocumentProvenance(it.structure.rootDocumentId, it.structure.rootDocumentTitle) }
            .distinct()
        // Unknown provenance FAILS CLOSED: a chunk we cannot attribute is the least safe
        // input to concatenate, not an excuse to skip the check.
        if (documents.size > 1) {
            return "Section '$sectionTitle' exists in ${documents.size} documents: " +
                documents.joinToString("; ") { it.describe() } +
                (documentTitle?.let {
                    ". The documentTitle '$it' still matches more than one — pass a longer, " +
                        "more specific substring"
                } ?: ". Call readSection again with a documentTitle") +
                " to pick ONE."
        }
        val rendered = chunks.map { "Chunk ID: ${it.id}\nContent: ${it.text}\n" }
        // Truncate at a chunk boundary, and report ONLY the chunks that survived it. Full score:
        // the model asked for this section by name, so what it was shown is evidence exactly as a
        // search hit is — but reporting a chunk it never saw lets an attribution check treat an
        // ungrounded quote as grounded, which is the same failure in the other direction.
        val shown = rendered
            .runningFold(0) { chars, chunkText -> chars + chunkText.length + 1 }
            .drop(1)
            .takeWhile { it <= maxReadSectionChars }
            .size
            .coerceAtLeast(1)
        val results = chunks.take(shown).map { SimpleSimilaritySearchResult<Retrievable>(it, 1.0) }
        resultsListener?.onResultsEvent(ResultsEvent(this, "readSection: $sectionTitle", results, Duration.ofMillis(ms)))
        val text = rendered.take(shown).joinToString("\n")
        if (shown < chunks.size || text.length > maxReadSectionChars) {
            val what = if (shown < chunks.size) {
                "showing $shown of ${chunks.size} chunks"
            } else {
                // A single chunk over the cap: the model sees part of a chunk reported whole.
                "one chunk of ${text.length} chars cut at $maxReadSectionChars"
            }
            return text.take(maxReadSectionChars) +
                "\n\n[TRUNCATED — $what. The chunks above are in document order; " +
                "use vectorSearch or textSearch for the rest, or broadenChunk from the last chunk shown.]"
        }
        return text
    }

    /**
     * Which document a section's chunks came from. Identity is the root document id — the
     * title is what the model is shown, but two documents may legitimately share one.
     */
    private data class DocumentProvenance(
        val id: String?,
        val title: String?,
    ) {
        fun describe(): String = when {
            title != null -> "'$title'"
            id != null -> "document $id"
            else -> "an unattributed document"
        }
    }

    companion object {
        const val DEFAULT_MAX_READ_SECTION_CHARS = 25_000
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T : Retrievable> TextSearch.textSearchWithFilter(
    request: TextSimilaritySearchRequest,
    clazz: Class<T>,
    metadataFilter: PropertyFilter?,
    entityFilter: EntityFilter?,
): List<SimilarityResult<T>> = when {
    metadataFilter == null && entityFilter == null -> textSearch(request, clazz)
    this is FilteringTextSearch -> textSearchWithFilter(request, clazz, metadataFilter, entityFilter)
    else -> PostFilteringSearch.search(
        request,
        metadataFilter,
        entityFilter,
        TopKInflationStrategy.DEFAULT,
    ) { inflatedRequest ->
        textSearch(inflatedRequest, clazz)
    } as List<SimilarityResult<T>>
}

/**
 * Tools to perform text search operations with the syntax supported by
 * the backing [TextSearch] store.
 *
 * Implements [Tool] directly (rather than exposing an `@LlmTool`-annotated
 * method) so the LLM-visible description can be **composed at construction
 * time from [TextSearch.luceneSyntaxNotes]**. The previous `@LlmTool`-driven
 * path hardcoded a Lucene-syntax description that contradicted stores like
 * `PgVectorStore` ("PostgreSQL substring matching only") or
 * `DirectoryTextSearch` ("Not supported"); see
 * [embabel/embabel-agent#1298](https://github.com/embabel/embabel-agent/issues/1298).
 *
 * One source of truth: the tool's own description carries the syntax notes.
 * [com.embabel.agent.rag.tools.ToolishRag.notes] no longer duplicates them.
 */
internal class TextSearchTools @JvmOverloads constructor(
    private val textSearch: TextSearch,
    private val searchFor: List<Class<out Retrievable>> = listOf(Chunk::class.java),
    private val metadataFilter: PropertyFilter? = null,
    private val entityFilter: EntityFilter? = null,
    private val resultsListener: ResultsListener? = null,
    private val searchDefaults: SearchDefaults = SearchDefaults.DEFAULT,
    private val resultExpander: ResultExpander? = null,
) : SearchTools, Tool {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = jacksonObjectMapper()

    // Deferred so just constructing [TextSearchTools] with a (non-relaxed)
    // mock doesn't trigger `luceneSyntaxNotes` access. Tests that exercise
    // search behaviour can keep their existing minimal mocks; only tests
    // that actually inspect the description need to stub the property.
    override val definition: Tool.Definition by lazy {
        Tool.Definition(
            name = "textSearch",
            description = buildDescription(textSearch.luceneSyntaxNotes),
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string(
                    name = "query",
                    description = buildQueryParamDescription(textSearch.luceneSyntaxNotes),
                ),
                Tool.Parameter.integer(
                    name = "topK",
                    description = "Maximum number of results to return.",
                ),
                // Optional — mirrors [VectorSearchTools.vectorSearch]. See [SearchDefaults].
                Tool.Parameter.double(
                    name = "threshold",
                    description = "Optional similarity floor from 0 to 1. Omit this unless you need to exclude weak matches: BM25 scores are corpus-relative, so ranking is what selects results.",
                    required = false,
                ),
            ),
        )
    }

    override fun call(input: String): Tool.Result = try {
        @Suppress("UNCHECKED_CAST")
        val params = objectMapper.readValue(input, Map::class.java) as Map<String, Any?>
        val query = (params["query"] as? String)
            ?: return Tool.Result.error("'query' parameter is required")
        val topK = (params["topK"] as? Number)?.toInt()
            ?: return Tool.Result.error("'topK' parameter is required")
        // Absent (or non-numeric, e.g. a JSON null) threshold falls back to the
        // configured floor rather than erroring — the parameter is optional.
        val threshold = (params["threshold"] as? Number)?.toDouble()
            ?: searchDefaults.textSimilarityThreshold
        Tool.Result.text(textSearch(query, topK, threshold))
    } catch (e: Exception) {
        Tool.Result.error("textSearch failed: ${e.message}")
    }

    /**
     * Public so unit tests can drive the search without going through the
     * JSON [call] entry point. Same shape as the previous `@LlmTool` method
     * — preserved on purpose so existing tests at this surface still work.
     */
    fun textSearch(
        query: String,
        topK: Int,
        threshold: ZeroToOne = searchDefaults.textSimilarityThreshold,
    ): String {
        logger.info(
            "Performing text search with query='{}', topK={}, threshold={}, types={}, metadataFilter={}, entityFilter={}",
            query, topK, threshold, searchFor.map { it.simpleName }, metadataFilter, entityFilter
        )

        val request = TextSimilaritySearchRequest(query, threshold, topK)
        val (hits, ms) = time {
            searchForAllTypes(request)
        }
        // Warn on the HITS, not the expanded set: the threshold suppressed matches, and
        // neighbours added afterwards would mask exactly the emptiness worth warning about.
        Bm25Normalization.warnIfThresholdSuppressedResults(logger, query, threshold, hits.size)
        val results = hits.withNeighbours(resultExpander, searchDefaults.expandNeighbours)
        resultsListener?.onResultsEvent(ResultsEvent(this, query, results, Duration.ofMillis(ms)))
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.fromList<Retrievable>(results))
    }

    private fun searchForAllTypes(request: TextSimilaritySearchRequest): List<SimilarityResult<out Retrievable>> {
        val allResults = searchFor.flatMap { clazz ->
            textSearch.textSearchWithFilter(request, clazz, metadataFilter, entityFilter)
        }
        return deduplicateByIdKeepingHighestScore(allResults)
    }

    companion object {
        /**
         * Compose the tool's top-level description from the store's
         * [TextSearch.luceneSyntaxNotes]. When the store reports nothing,
         * the syntax line is omitted entirely so the LLM doesn't see a
         * trailing "Query syntax: " with empty contents.
         */
        internal fun buildDescription(syntaxNotes: String): String {
            val base = "Perform BM25 text search. Specify topK and similarity threshold from 0-1."
            return if (syntaxNotes.isBlank()) base
            else "$base\n\nQuery syntax: ${syntaxNotes.trim()}"
        }

        /**
         * Compose the `query` parameter description from the store's
         * [TextSearch.luceneSyntaxNotes]. Falls back to a generic line
         * when nothing is reported.
         */
        internal fun buildQueryParamDescription(syntaxNotes: String): String =
            if (syntaxNotes.isBlank()) "The search query."
            else "The search query. Syntax: ${syntaxNotes.trim()}"
    }
}

internal class RegexSearchTools(
    private val regexSearch: RegexSearchOperations,
    private val metadataFilter: PropertyFilter? = null,
    private val entityFilter: EntityFilter? = null,
    private val resultsListener: ResultsListener? = null,
) : SearchTools {

    @LlmTool(description = "Perform regex search across content elements. Specify topK")
    fun regexSearch(
        regex: String,
        topK: Int,
    ): String {
        loggerFor<RegexSearchTools>().info(
            "Performing regex search with regex='{}', topK={}, metadataFilter={}, entityFilter={}",
            regex, topK, metadataFilter, entityFilter
        )
        val start = Instant.now()
        val results = searchWithFilter(Regex(regex), topK)
        val runningTime = Duration.between(start, Instant.now())
        resultsListener?.onResultsEvent(ResultsEvent(this, regex, results, runningTime))
        return SimpleRetrievableResultsFormatter.formatResults(SimilarityResults.fromList(results))
    }

    private fun searchWithFilter(
        regex: Regex,
        topK: Int,
    ): List<SimilarityResult<Chunk>> {
        if (metadataFilter == null && entityFilter == null) {
            return regexSearch.regexSearch(regex, topK, Chunk::class.java)
        }

        // If backend supports native filtering, use it
        if (regexSearch is FilteringRegexSearch) {
            return regexSearch.regexSearchWithFilter(regex, topK, Chunk::class.java, metadataFilter, entityFilter)
        }

        // Fallback: inflate topK, search, post-filter, take topK
        return PostFilteringSearch.regexSearch(
            topK,
            metadataFilter,
            entityFilter,
            TopKInflationStrategy.DEFAULT
        ) { inflatedTopK ->
            regexSearch.regexSearch(regex, inflatedTopK, Chunk::class.java)
        }
    }
}

/**
 * Tools to check if a type is supported by this store.
 */
internal class TypeRetrievalTools(
    private val typeRetrievalOperations: TypeRetrievalOperations,
) : SearchTools {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    @LlmTool(description = "Check if a type is supported for retrieval. Provide the simple class name.")
    fun isTypeSupported(
        @LlmTool.Param(description = "The type (usually simple class) name to check") typeName: String,
    ): String {
        logger.info("Checking if type '{}' is supported", typeName)
        return if (typeRetrievalOperations.supportsType(typeName)) {
            "Type '$typeName' is supported"
        } else {
            "Type '$typeName' is not supported by this store"
        }
    }
}

/**
 * Tools to retrieve items by ID from the store.
 */
internal class FinderTools(
    private val finderOperations: FinderOperations,
) : SearchTools {

    private val logger: Logger = LoggerFactory.getLogger(javaClass)

    @LlmTool(description = "Retrieve an item by its ID. Provide the type as a simple class name.")
    fun findById(
        @LlmTool.Param(description = "The ID of the item to retrieve") id: String,
        @LlmTool.Param(description = "The type name (usually simple class name) of the item") typeName: String,
    ): String {
        logger.info("Finding retrievable by id='{}', type='{}'", id, typeName)

        if (!finderOperations.supportsType(typeName)) {
            return "Type '$typeName' is not supported by this store"
        }

        val result = finderOperations.findById<Retrievable>(id, typeName)
        return if (result != null) {
            "Found ${result.javaClass.simpleName} with id '$id': ${result.infoString(verbose = true)}"
        } else {
            "No item found with id '$id' of type '$typeName'"
        }
    }
}

/**
 * Deduplicate results by ID, keeping the result with the highest score for each unique ID.
 */
internal fun deduplicateByIdKeepingHighestScore(
    results: List<SimilarityResult<out Retrievable>>,
): List<SimilarityResult<out Retrievable>> =
    results
        .groupBy { it.match.id }
        .map { (_, group) -> group.maxBy { it.score } }
        .sortedByDescending { it.score }
