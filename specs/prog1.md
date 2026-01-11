# Progressive Tools Implementation - Phase 1 Summary

## Completed Work

### Core Framework-Agnostic Tool Loop

Created Embabel's own tool execution loop that is completely decoupled from Spring AI:

#### New Files Created

1. **`spi/toolloop/LlmCall.kt`** - Framework-agnostic interface for single LLM calls:
   - `SingleLlmCallResult` - Result container with message and text content
   - `SingleLlmCaller` - Interface for making single LLM calls without tool execution

2. **`spi/toolloop/EmbabelToolLoop.kt`** - Core tool loop implementation:
   - Uses `SingleLlmCaller` for LLM communication
   - Uses Embabel's `Tool` interface (not Spring AI's `ToolCallback`)
   - Supports dynamic tool injection via strategies
   - Configurable max iterations
   - Handles tool execution and result collection

3. **`spi/toolloop/ToolInjectionStrategy.kt`** - Strategy for dynamic tool injection:
   - `ToolInjectionStrategy` - Interface for evaluating tool results and injecting new tools
   - `ToolInjectionContext` - Context provided to strategies
   - `ToolCallResult` - Result of a single tool call

4. **`spi/toolloop/ToolLoopResult.kt`** - Result container:
   - Parsed result
   - Conversation history
   - Total iterations
   - List of injected tools

5. **`spi/toolloop/ToolLoopExceptions.kt`** - Custom exceptions:
   - `ToolNotFoundException` - When LLM requests unknown tool
   - `MaxIterationsExceededException` - When iteration limit reached

6. **`chat/ToolMessages.kt`** - Tool-related message types:
   - `ToolCall` - Represents a tool call requested by the assistant
   - `AssistantMessageWithToolCalls` - Assistant message that includes tool calls
   - `ToolResultMessage` - Message containing tool execution result

#### Spring AI Integration

7. **`spi/support/springai/SpringAiSingleLlmCaller.kt`** - Spring AI implementation:
   - Implements `SingleLlmCaller` using Spring AI's `ChatModel`
   - Converts Embabel `Tool` to Spring AI `ToolCallback` for LLM communication
   - Uses `ToolCallingChatOptions` to pass tool definitions

8. **Modified `spi/support/springai/ChatClientLlmOperations.kt`**:
   - Added `doTransformWithEmbabelToolLoop()` method
   - Added `doTransformWithSpringAi()` preserving original implementation
   - Routing controlled by `LlmInteraction.useEmbabelToolLoop` flag
   - Converts Spring AI `ToolCallback` to Embabel `Tool` when needed

9. **Modified `spi/support/springai/converter.kt`**:
   - Added handling for `AssistantMessageWithToolCalls` and `ToolResultMessage`
   - Text-only fallback for tool messages (full support in Phase 3)

10. **Modified `spi/LlmOperations.kt`**:
    - Added `useEmbabelToolLoop` flag to `LlmInteraction`
    - Defaults to `false` until Phase 3 is complete

### Tests

11. **`test/spi/toolloop/EmbabelToolLoopTest.kt`**:
    - Tests for basic execution without tools
    - Tests for tool execution
    - Tests for tool injection via strategy
    - Tests for max iterations handling
    - Uses `MockSingleLlmCaller` and `MockTool` for testing

## Current State

- **Default behavior**: Uses Spring AI's internal tool loop (`useEmbabelToolLoop = false`)
- **New tool loop**: Available but opt-in via `useEmbabelToolLoop = true`
- **All 1532 existing tests pass**
- **Framework decoupling**: Tool loop uses Embabel's `Tool` interface, not Spring AI's `ToolCallback`

## What's Still To Do

### Phase 2 - Enhanced Features
- [ ] Event emission from tool loop (for observability)
- [ ] Usage tracking and recording
- [ ] Retry logic for tool calls
- [ ] Timeout handling per tool call

### Phase 3 - Full Integration
- [ ] Make `useEmbabelToolLoop = true` the default
- [ ] Full tool message conversion (not just text fallback)
- [ ] Integration with existing event system
- [ ] Documentation

### Phase 4 - Progressive Disclosure
- [ ] Implement skill-based tool injection strategies
- [ ] Dynamic tool unlocking based on conversation context
- [ ] Tool grouping and lazy loading

## Key Design Decisions

1. **Framework Agnostic**: `SingleLlmCaller` and `Tool` interfaces are not tied to Spring AI
2. **Opt-in Flag**: `useEmbabelToolLoop` allows gradual migration
3. **Adapter Pattern**: Spring AI `ToolCallback` ↔ Embabel `Tool` adapters in both directions
4. **Strategy Pattern**: `ToolInjectionStrategy` allows pluggable tool injection logic
