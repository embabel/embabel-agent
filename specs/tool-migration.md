# Tool Migration: ToolCallback to Tool

## Overview

Migrate from Spring AI's `ToolCallback` to Embabel's framework-agnostic `Tool` interface throughout the codebase. The only place `ToolCallback` should remain is in `ChatClientLlmOperations` where it interfaces with Spring AI.

## Current State

The codebase converts to `ToolCallback` too early - at tool definition time rather than at the Spring AI boundary:

| Interface | Location | Current | Target |
|-----------|----------|---------|--------|
| `ToolCallbackSpec` | `ToolConsumer.kt:149-156` | `List<ToolCallback>` | `List<Tool>` |
| `ToolConsumer` | `ToolConsumer.kt:181` | extends `ToolCallbackConsumer` | extends `ToolSpec` |
| `ToolGroup` | `ToolConsumer.kt:271` | extends `ToolCallbackPublisher` | extends `ToolPublisher` |
| `LlmInteraction` | `LlmOperations.kt:114` | `toolCallbacks: List<ToolCallback>` | `tools: List<Tool>` |
| `LlmCall` | `LlmOperations.kt:64` | extends `ToolConsumer` | uses `List<Tool>` |
| `ToolDecorator` | `ToolDecorator.kt:26` | `ToolCallback -> ToolCallback` | `Tool -> Tool` |

## Legitimate ToolCallback Usage (Keep)

- `ChatClientLlmOperations` - Spring AI integration point
- Spring AI decorator wrappers that require ToolContext binding

## Implementation Phases

### Phase 1: Create Parallel Tool Interfaces [COMPLETE]

**Status**: COMPLETE

Add new interfaces alongside existing ones for backward compatibility:

- [x] Create `ToolSpec` interface with `tools: List<Tool>` property
- [x] Create `ToolPublisher` interface extending `ToolSpec`
- [x] Create `ToolSpecConsumer` interface
- [x] Add `tools` property to `ToolGroup` (alongside `toolCallbacks`)
- [x] Add `ToolGroup.ofTools()` factory method for native Tool creation
- [x] Add `resolveTools()` to `ToolConsumer` (alongside `resolveToolCallbacks()`)
- [x] Update `resolveToolCallbacks()` to include native tools from `ToolGroup.tools`
- [x] Make `ToolConsumer` implement `ToolSpecConsumer`

**Files modified:**
- `ToolConsumer.kt`

### Phase 2: Migrate ToolGroup [COMPLETE]

**Status**: COMPLETE (merged with Phase 1)

- [x] `ToolGroup` now implements `ToolPublisher` and has `tools` property
- [x] `ToolGroup.ofTools()` factory method creates groups from native Tools
- [x] Resolution logic in `resolveToolCallbacks()` handles both `tools` and `toolCallbacks`

**Files modified:**
- `ToolConsumer.kt`

### Phase 3: Migrate LlmInteraction and LlmCall [COMPLETE]

**Status**: COMPLETE

- [x] Add `tools: List<Tool>` to `LlmInteraction`
- [x] Add `tools: List<Tool>` to `LlmCallImpl`
- [x] Update `LlmInteraction.from()` to copy `tools` from source
- [x] Update `AbstractLlmOperations` to clear `tools` after resolution (to avoid double-counting)

**Files modified:**
- `LlmOperations.kt`
- `AbstractLlmOperations.kt`

### Phase 4: Create Tool-based Decorator [DEFERRED]

**Status**: DEFERRED - The current decorator chain is tightly integrated with Spring AI's ToolContext system.
Moving decoration to the Tool level would require significant refactoring. For now, the decorators
remain at the ToolCallback level, which is acceptable since they're only used in ChatClientLlmOperations.

- [ ] Create `ToolDecorator` interface that works with `Tool`
- [ ] Create framework-agnostic decorator implementations
- [ ] Keep Spring AI-specific decorators for ToolContext binding only

**Files to modify:**
- `ToolDecorator.kt`
- Create new decorator implementations

### Phase 5: Isolate Spring AI Conversion [COMPLETE]

**Status**: COMPLETE

- [x] Created `safelyGetTools()` and `safelyGetToolsFrom()` that return native `List<Tool>`
- [x] Created `RenamedTool` class for renaming tools at the Tool level
- [x] `safelyGetToolCallbacks()` and `safelyGetToolCallbacksFrom()` now `internal` visibility
- [x] External modules (MCP) updated to use `safelyGetTools()` + `toSpringToolCallbacks()`
- [x] Now handles direct `Tool` instances in addition to `ToolCallback` and annotated methods
- [x] Updated tests to properly mock ToolCallback properties

**Files modified:**
- `springAiUtils.kt` - functions now internal
- `McpToolExport.kt` - uses `safelyGetTools()` + `toSpringToolCallback()`
- `McpSyncServerConfiguration.kt` - uses `safelyGetToolsFrom()` + `toSpringToolCallbacks()`
- `McpAsyncServerConfiguration.kt` - uses `safelyGetToolsFrom()` + `toSpringToolCallbacks()`

### Phase 6: Cleanup Deprecated Code [COMPLETE]

**Status**: COMPLETE

Removed interfaces that exposed `toolCallbacks` as part of the public API:

- [x] Removed `ToolCallbackSpec` interface
- [x] Removed `ToolCallbackConsumer` interface
- [x] `ToolConsumer` no longer extends `ToolCallbackConsumer`
- [x] `ToolCallbackPublisher` is now standalone (for `ToolGroup` backward compatibility)
- [x] `LlmInteraction.toolCallbacks` is now internal-only (not from parent interface)
- [x] Updated tests to use `tools` instead of `toolCallbacks`

**Kept for backward compatibility:**
- `ToolCallbackPublisher` interface (used by `ToolGroup` for Spring AI tools)
- `ToolGroup.toolCallbacks` property (for tool groups created from Spring AI tools)
- `LlmInteraction.toolCallbacks` (internal use only, populated during resolution)

## Testing Strategy

- **Minimize e2e test changes**: Only modify tests to prove behavior is not broken
- **Add unit tests**: For new interfaces and conversion logic
- **Build verification**: Run Maven build after each phase

## Progress Log

### 2026-01-12

- Created migration plan
- **Phase 1 COMPLETE**: Added `ToolSpec`, `ToolSpecConsumer`, `ToolPublisher` interfaces
- **Phase 2 COMPLETE**: `ToolGroup` now implements `ToolPublisher` with `tools` property
- **Phase 3 COMPLETE**: `LlmInteraction` and `LlmCall` now have `tools` property
- **Phase 4 DEFERRED**: Tool-based decorator pattern deferred (Spring AI ToolContext integration)
- **Phase 5 COMPLETE**: Created `safelyGetTools()` returning native `Tool` list
- **Phase 6 COMPLETE**: Removed `ToolCallbackSpec`, `ToolCallbackConsumer`; `toolCallbacks` now internal-only

## Summary

The migration establishes framework-agnostic tool handling:

1. **New interfaces**: `ToolSpec`, `ToolSpecConsumer`, `ToolPublisher` for native Tool support
2. **Primary API**: `tools: List<Tool>` is now the primary way to provide tools
3. **Removed from public API**: `ToolCallbackSpec`, `ToolCallbackConsumer` interfaces removed
4. **Internal use only**: `LlmInteraction.toolCallbacks` exists but is populated internally during resolution
5. **Resolution logic**: `resolveToolCallbacks()` converts native tools; `resolveTools()` for native access
6. **Public utility**: `safelyGetTools()` extracts native Tools (public API)
7. **Internal utility**: `safelyGetToolCallbacks()` is internal - external code uses `safelyGetTools()` + `toSpringToolCallbacks()`
8. **ToolGroup backward compat**: `ToolGroup` still supports `toolCallbacks` for Spring AI tool groups
9. **Spring @Tool support**: Users can still pass Spring `@Tool` annotated objects - they're converted to native Tools internally

