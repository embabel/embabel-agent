# PlaybookTool

A tool with conditional tool unlocking that uses an LLM to orchestrate sub-tools.

> **Status**: Experimental (`@ApiStatus.Experimental`)

## Overview

Unlike `AgenticTool` which makes all tools available immediately, `PlaybookTool` allows tools to be progressively
unlocked based on conditions:

- **Prerequisites**: unlock after other tools have been called
- **Artifacts**: unlock when certain artifact types are produced
- **Custom predicates**: unlock based on arbitrary conditions

This provides more predictable LLM behavior by guiding it through a structured sequence of available tools.

## Java Usage

### Basic Setup

```java
import com.embabel.agent.api.tool.playbook.PlaybookTool;
import com.embabel.agent.api.tool.playbook.UnlockCondition;
import com.embabel.agent.api.tool.Tool;

// Create tools
Tool searchTool = createSearchTool();
Tool analyzeTool = createAnalyzeTool();
Tool summarizeTool = createSummarizeTool();

// Build the playbook
var playbook = new PlaybookTool("researcher", "Research and analyze topics")
    .withTools(searchTool)                              // Always available
    .withTool(analyzeTool).unlockedBy(searchTool)       // Unlocks after search
    .withTool(summarizeTool).unlockedBy(analyzeTool);   // Unlocks after analyze
```

### Unlock Conditions

#### After Tool Prerequisites

```java
// Single prerequisite
.withTool(analyzeTool)
    .unlockedBy(searchTool)

// Multiple prerequisites - ALL must be called (AND)
.withTool(reportTool)
    .unlockedByAll(searchTool, analyzeTool)

// Multiple prerequisites - ANY one is sufficient (OR)
.withTool(processTool)
    .unlockedByAny(searchTool, fetchTool)
```

#### After Artifact Produced

```java
// Unlock when a Document artifact is produced
.withTool(summarizeTool)
    .unlockedByArtifact(Document.class)

// Unlock when any artifact matches a predicate
.withTool(processTool)
    .unlockedByArtifactMatching(a -> a instanceof Document && ((Document) a).isValid())
```

#### Custom Predicate

```java
// Unlock after 3 iterations
.withTool(finalizeTool)
    .unlockedWhen(ctx -> ctx.getIterationCount() >= 3)

// Unlock when specific artifacts exist
.withTool(reportTool)
    .unlockedWhen(ctx -> ctx.getArtifacts()
        .stream()
        .anyMatch(a -> a instanceof AnalysisResult))
```

#### Composite Conditions

```java
// All conditions must be met
UnlockCondition allOf = UnlockCondition.AllOf(
    UnlockCondition.afterTools("search"),
    UnlockCondition.onArtifact(Document.class)
);
.withTool(advancedTool)
    .unlockedWhen(allOf)

// Any condition is sufficient
UnlockCondition anyOf = UnlockCondition.AnyOf(
    UnlockCondition.afterTools("search"),
    UnlockCondition.afterTools("fetch")
);
.withTool(processTool)
    .unlockedWhen(anyOf)
```

### Configuration

```java
var playbook = new PlaybookTool("researcher", "Research topics")
    .withTools(searchTool)
    .withTool(analyzeTool).unlockedBy(searchTool)
    .withLlm(new LlmOptions().withTemperature(0.7))
    .withMaxIterations(15)
    .withSystemPrompt("You are a research assistant...");
```

### Using with PromptRunner

```java
// In an action method
@Action
public Result research(ResearchRequest request, OperationContext context) {
    var playbook = new PlaybookTool("researcher", "Research and analyze")
        .withTools(searchTool, fetchTool)
        .withTool(analyzeTool).unlockedBy(searchTool)
        .withTool(summarizeTool).unlockedBy(analyzeTool);

    return context.ai()
        .withTool(playbook)
        .creating(Result.class)
        .fromPrompt("Research: " + request.getTopic());
}
```

## Kotlin Usage

Kotlin supports a curried syntax in addition to the fluent API:

```kotlin
// Curried syntax (Kotlin only)
PlaybookTool("researcher", "Research and analyze topics")
    .withTools(searchTool, fetchTool)
    .withTool(analyzeTool)(searchTool)          // Curried: unlocks after search
    .withTool(summarizeTool)(analyzeTool)       // Curried: unlocks after analyze

// Artifact-based unlock with KClass
    .withTool(summarizeTool)(Document::class)

// Fluent syntax (same as Java)
    .withTool(analyzeTool).unlockedBy(searchTool)
```

## How It Works

1. **Unlocked tools** are always available to the LLM
2. **Locked tools** are visible but return a "not yet available" message with hints when called before their conditions
   are met
3. The LLM receives feedback guiding it to use prerequisite tools first
4. As conditions are satisfied, locked tools become functional
5. Artifacts produced by any tool are tracked and can trigger artifact-based unlocks

## Comparison with AgenticTool

| Feature           | AgenticTool            | PlaybookTool         |
|-------------------|------------------------|----------------------|
| Tool availability | All at once            | Progressive          |
| Execution order   | LLM decides freely     | Guided by conditions |
| Predictability    | Lower                  | Higher               |
| Use case          | Flexible orchestration | Structured workflows |

## Classes

- `PlaybookTool` - Main tool class
- `ToolRegistration` - Fluent builder for locked tools
- `UnlockCondition` - Sealed interface for unlock conditions
- `PlaybookContext` - Context for evaluating conditions
- `PlaybookState` - Tracks tool calls and artifacts
