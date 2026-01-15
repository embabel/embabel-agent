# MatryoshkaTool: Progressive Tool Disclosure

## Overview

Named after Russian nesting dolls, a `MatryoshkaTool` is a special tool that describes itself at a high level and when invoked, exposes its inner tools. This enables progressive tool disclosure - presenting a simplified interface initially, then revealing more granular tools on demand.

## Motivation

Large tool sets can overwhelm LLMs, leading to:
- Poor tool selection due to choice paralysis
- Increased token usage for tool descriptions
- Confusion between similar tools

Progressive disclosure addresses this by grouping related tools behind a facade. The LLM first sees a category-level tool (e.g., "database_operations"), then gains access to specific tools (e.g., "query_table", "insert_record") when it expresses intent.

## Design

### MatryoshkaTool Interface

```kotlin
interface MatryoshkaTool : Tool {
    /**
     * The inner tools that will be exposed when this tool is invoked.
     */
    val innerTools: List<Tool>

    /**
     * Whether to remove the MatryoshkaTool after invocation.
     * Default true - the facade is replaced by its contents.
     * Set false to keep the facade available (for re-invocation with different args).
     */
    val removeOnInvoke: Boolean get() = true
}
```

### Tool Selection via Arguments

MatryoshkaTools can optionally filter which inner tools to expose based on invocation arguments:

```kotlin
class SelectableMatryoshkaTool(
    override val definition: Tool.Definition,
    override val innerTools: List<Tool>,
    private val selector: (String) -> List<Tool>,
) : MatryoshkaTool {

    override fun call(input: String): Tool.Result {
        // The actual tool selection happens in the injection strategy
        // This method just returns a helpful message
        return Tool.Result.text("Enabled tools: ${selectedTools(input).map { it.definition.name }}")
    }

    fun selectedTools(input: String): List<Tool> = selector(input)
}
```

### Injection Strategy

The `MatryoshkaToolInjectionStrategy` watches for MatryoshkaTool invocations:

```kotlin
class MatryoshkaToolInjectionStrategy : ToolInjectionStrategy {

    override fun evaluateToolResult(context: ToolInjectionContext): ToolInjectionResult {
        val invokedTool = context.currentTools.find {
            it.definition.name == context.lastToolCall.toolName
        }

        if (invokedTool !is MatryoshkaTool) {
            return ToolInjectionResult.noChange()
        }

        // Determine which tools to inject
        val selectedTools = if (invokedTool is SelectableMatryoshkaTool) {
            invokedTool.selectedTools(context.lastToolCall.toolInput)
        } else {
            invokedTool.innerTools
        }

        // Determine tools to remove (if removeOnInvoke)
        val toolsToRemove = if (invokedTool.removeOnInvoke) {
            listOf(invokedTool)
        } else {
            emptyList()
        }

        return ToolInjectionResult(
            toolsToAdd = selectedTools,
            toolsToRemove = toolsToRemove,
        )
    }
}
```

### ToolInjectionResult Enhancement

The current `ToolInjectionStrategy` only supports adding tools. For MatryoshkaTool to work, we need removal support:

```kotlin
data class ToolInjectionResult(
    val toolsToAdd: List<Tool> = emptyList(),
    val toolsToRemove: List<Tool> = emptyList(),
) {
    companion object {
        fun noChange() = ToolInjectionResult()
        fun add(tools: List<Tool>) = ToolInjectionResult(toolsToAdd = tools)
        fun replace(remove: Tool, add: List<Tool>) = ToolInjectionResult(
            toolsToRemove = listOf(remove),
            toolsToAdd = add,
        )
    }
}
```

### DefaultToolLoop Changes

Update the tool loop to handle removal:

```kotlin
// In DefaultToolLoop.execute(), after evaluating injection strategy:
val injectionResult = injectionStrategy.evaluateToolResult(context)
if (injectionResult.toolsToRemove.isNotEmpty() || injectionResult.toolsToAdd.isNotEmpty()) {
    // Remove tools
    injectionResult.toolsToRemove.forEach { toRemove ->
        availableTools.removeIf { it.definition.name == toRemove.definition.name }
    }
    // Add tools
    availableTools.addAll(injectionResult.toolsToAdd)
    injectedTools.addAll(injectionResult.toolsToAdd)
    // ... logging
}
```

## Usage Examples

### Simple Facade

```kotlin
val databaseTool = MatryoshkaTool.of(
    name = "database_operations",
    description = "Use this tool to work with the database. Invoke to reveal specific operations.",
    innerTools = listOf(
        Tool.of("query_table", "Execute a SQL query", ...) { ... },
        Tool.of("insert_record", "Insert a new record", ...) { ... },
        Tool.of("update_record", "Update existing records", ...) { ... },
    )
)
```

### Category-Based Selection

```kotlin
val fileTool = MatryoshkaTool.selectable(
    name = "file_operations",
    description = "File operations. Pass category: 'read' or 'write' to reveal relevant tools.",
    innerTools = mapOf(
        "read" to listOf(readFileTool, listDirectoryTool, searchFilesTool),
        "write" to listOf(writeFileTool, deleteFileTool, moveFileTool),
    )
) { input ->
    val category = parseCategory(input)
    innerTools[category] ?: innerTools.values.flatten()
}
```

### Nested Matryoshka (Advanced)

```kotlin
val adminTool = MatryoshkaTool.of(
    name = "admin_operations",
    description = "Administrative operations. Invoke to see categories.",
    innerTools = listOf(
        MatryoshkaTool.of(
            name = "user_management",
            description = "User management operations",
            innerTools = listOf(createUserTool, deleteUserTool, ...),
        ),
        MatryoshkaTool.of(
            name = "system_config",
            description = "System configuration operations",
            innerTools = listOf(updateConfigTool, backupTool, ...),
        ),
    )
)
```

## Implementation Plan

### Phase 1: Core Interface and Strategy
- [ ] Create `MatryoshkaTool` interface in `com.embabel.agent.api.tool`
- [ ] Create `ToolInjectionResult` to support add/remove
- [ ] Update `ToolInjectionStrategy.evaluateToolResult` to return `ToolInjectionResult`
- [ ] Create `MatryoshkaToolInjectionStrategy`

### Phase 2: Tool Loop Integration
- [ ] Update `DefaultToolLoop` to handle tool removal
- [ ] Add tests for MatryoshkaTool injection

### Phase 3: Builder API
- [ ] Add `MatryoshkaTool.of()` factory methods
- [ ] Add `MatryoshkaTool.selectable()` for argument-based selection
- [ ] Add Java-friendly builders

## Backward Compatibility

The current `ToolInjectionStrategy` returns `List<Tool>`. We'll need to either:
1. **New method**: Add `evaluateToolResultV2()` returning `ToolInjectionResult`, default impl wraps old method
2. **Wrapper**: Keep interface same, use wrapper to detect MatryoshkaTool invocations separately

Option 1 is cleaner. Existing strategies continue to work via default implementation.

## Open Questions

1. **Nested depth limit?** Should we limit nesting depth to prevent infinite matryoshka?
2. **Re-folding?** Should we support "folding" tools back into a MatryoshkaTool?
3. **Visibility to LLM?** Should the LLM be told about the nesting structure, or just see flat tool lists?
