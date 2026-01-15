# Split PromptRunner's responsibilities

## Overview

Introduce a `PromptExecutionDelegate` delegate that is used by `PromptRunner`, `ObjectCreator` and`TemplateOperations`.

## Current State

The default implementations of `ObjectCreator` and `TemplateOperations` delegate to `PromptRunner`, and therefore
`PromptRunner` needs to expose all functionality contained in these default implementations.

## Implementation Phases

### Phase 1: Extract interface for `TemplateOperations`

`TemplateOperations` is currently a class. Turn it an interface, and move all implementation logic to a new internal
class named `PromptRunnerTemplateOperations`, similarly to how `PromptRunnerObjectCreator` does.

### Phase 2: Introduce `PromptExecutionDelegate`

Determine the core functionality of `PromptRunner` and its delegates `PromptRunnerObjectCreator` and
`PromptRunnerTemplateOperations`.
This should only include methods and properties that cannot be expressed in terms of other methods, and should exclude
convenience methods.
Add a new interface `PromptExecutionDelegate` which exposes this core functionality.

### Phase 3: Migrate to `PromptExecutionDelegate`

Introduce new implementations of `PromptRunner`, `ObjectCreator` and `TemplateOperations` that use the
`PromptExecutionDelegate` as delegate.
Name these `DelegatingPromptRunner`, `DelegatingObjectCreator` and `DelegatingTemplateOperations` and make them
internal.
`DelegatingPromptRunner` should return `DelegatingObjectCreator` for `PromptRunner::creating` and
`DelegatingTemplateOperations` for `PromptRunner::withTemplate`.

### Phase 4: Remove unnecessary members from `PromptRunner`

Remove all functionality that is exposed in `ObjectCreator` or `TemplateOperations` from `PromptRunner`. For instance,
`PromptRunner::withPropertyFilter`, `PropertyFilter::withValidation`.

### Phase 5: Create a default implementation of `PromptExecutionDelegate`

Create an internal implementation of `PromptExecutionDelegate` named `OperationContextDelegate` and that uses the
`OperationContext` as a delegate, similarly as `OperationContextPromptRunner` does.

### Phase 6: Replace usage of old implementations with new ones

Replace usage of the old default implementations of the interfaces changed to the new ones:

- `OperationContextPromptRunner` usage should become `OperationContextDelegate`

## Considerations

- Do not break backwards compatibility, except for the members mentioned in step 4.
- Do not introduce cyclic dependencies between packages, and if possible remove current ones.

## Testing Strategy

- **Minimize e2e test changes**: Only modify tests to prove behavior is not broken
- **Add unit tests**: For new interfaces and conversion logic
- **Build verification**: Run Maven build after each phase

## Progress Log

### 2026-01-12

- Created migration plan

### 2026-01-15

- ✅ Phase 1 complete: Extracted `TemplateOperations` interface
    - Converted `TemplateOperations` class to interface
    - Created `PromptRunnerTemplateOperations` internal implementation
    - Updated `OperationContextPromptRunner` and `FakePromptRunner` to use new implementation
    - Build verified successfully

- ✅ Phase 2 complete: Introduced `PromptExecutionDelegate` interface
  - Analyzed core functionality used by `PromptRunner`, `PromptRunnerObjectCreator`, and
    `PromptRunnerTemplateOperations`
  - Identified primitive operations (execution methods, state properties, configuration methods)
  - Created `PromptExecutionDelegate` interface extending `LlmUse` with:
    - Core execution methods: `createObject`, `createObjectIfPossible`, `respond`, `evaluateCondition`
    - State properties: `toolObjects`, `messages`, `images`, plus inherited from `LlmUse`
    - Configuration methods: all `with*` methods returning `PromptExecutionDelegate`
  - Build verified successfully