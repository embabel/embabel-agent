# RAG Filtering

This package provides filter expressions for RAG (Retrieval-Augmented Generation) searches.

## Filter Hierarchy

```
PropertyFilter (sealed interface)
├── Eq, Ne, Gt, Gte, Lt, Lte  - Comparison filters
├── In, Nin                    - List membership filters
├── Contains                   - Substring match filter
├── And, Or, Not              - Logical combinators
└── EntityFilter (sealed interface, extends PropertyFilter)
    └── HasAnyLabel           - Label-based entity filtering
```

## PropertyFilter

`PropertyFilter` is designed for filtering on map-based properties:
- **Metadata filtering**: Applied to `Datum.metadata` map
- **Property filtering**: Applied to `NamedEntityData.properties` map

### Usage

```kotlin
import com.embabel.agent.rag.filter.PropertyFilter.Companion.*

// Simple equality
val ownerFilter = eq("owner", "alice")

// Numeric comparison
val scoreFilter = gte("score", 0.8)

// List membership
val statusFilter = `in`("status", listOf("active", "pending"))

// Fluent combination
val combined = (eq("owner", "alice") and gte("score", 0.8)) or eq("role", "admin")

// Negation
val excluded = !eq("status", "deleted")
```

## EntityFilter

`EntityFilter` extends `PropertyFilter` to add entity-specific filtering, particularly label-based filtering.

### HasAnyLabel

Filters entities that have at least one of the specified labels:

```kotlin
import com.embabel.agent.rag.filter.EntityFilter
import com.embabel.agent.rag.filter.PropertyFilter.Companion.eq
import com.embabel.agent.rag.filter.PropertyFilter.Companion.gte

// Filter by single label
val personFilter = EntityFilter.hasAnyLabel("Person")

// Filter by multiple labels (OR semantics - entity must have ANY of these labels)
val entityFilter = EntityFilter.hasAnyLabel("Person", "Organization")

// Combine HasAnyLabel with property filters using fluent API
val simpleCombo = EntityFilter.hasAnyLabel("Person") and eq("status", "active")

// Multiple conditions
val complexFilter = EntityFilter.hasAnyLabel("Person") and
    eq("status", "active") and
    gte("score", 0.8)

// OR combinations
val orFilter = EntityFilter.hasAnyLabel("Person") or eq("fallback", true)

// With negation
val notDeleted = EntityFilter.hasAnyLabel("Person") and !eq("status", "deleted")

// Complex grouping
val accessFilter = (EntityFilter.hasAnyLabel("Person", "Employee") and eq("active", true)) or
    eq("role", "admin")
```

Since `EntityFilter` extends `PropertyFilter`, all filter types share the same `and`, `or`, `not` operators and can be freely combined.

## Using Filters in Searches

### With NamedEntityDataRepository

```kotlin
// Vector search with entity filtering
val results = repository.vectorSearch(
    request = TextSimilaritySearchRequest("query", 0.7, 10),
    metadataFilter = PropertyFilter.eq("source", "news"),
    entityFilter = EntityFilter.hasAnyLabel("Person")
)

// Text search with entity filtering
val results = repository.textSearch(
    request = TextSimilaritySearchRequest("John", 0.5, 10),
    entityFilter = EntityFilter.hasAnyLabel("Person", "Employee")
)
```

### With ToolishRag

```kotlin
val rag = ToolishRag(
    name = "people-search",
    description = "Search for people",
    searchOperations = repository,
    metadataFilter = PropertyFilter.eq("tenant", tenantId),
    entityFilter = EntityFilter.hasAnyLabel("Person")
)
```

## In-Memory Filtering

When search backends don't support native filtering, `InMemoryPropertyFilter` provides fallback filtering:

```kotlin
// Filter results in memory
val filtered = InMemoryPropertyFilter.filterResults(
    results,
    metadataFilter = PropertyFilter.eq("source", "news"),
    entityFilter = EntityFilter.hasAnyLabel("Person")
)
```

## Native Filter Translation

Some backends support native filter translation:
- **Spring AI**: `PropertyFilter.toSpringAiExpression()` translates to Spring AI filter expressions
- **Neo4j**: Backends can translate to Cypher WHERE clauses

Note: `EntityFilter.HasAnyLabel` is typically handled via in-memory filtering as most vector stores don't have native label support.
