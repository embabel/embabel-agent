# StateMachineTool

A tool with explicit state-based tool availability that uses an LLM to orchestrate sub-tools.

> **Status**: Experimental (`@ApiStatus.Experimental`)

## Overview

Unlike `PlaybookTool` which uses unlock conditions, `StateMachineTool` uses explicit states defined by an enum:

- Tools are registered with specific states where they're available
- Tools can trigger transitions to other states
- The current state is tracked during execution
- Tools called in the wrong state return an informative error

This provides predictable, state-machine-style workflows where the available actions depend on the current state.

## Java Usage

### Basic Setup

```java
// Define states as an enum
enum OrderState { DRAFT, CONFIRMED, SHIPPED, DELIVERED }

// Build the state machine
var stateMachine = new StateMachineTool<>("orderProcessor", "Process orders", OrderState.class)
    .withInitialState(OrderState.DRAFT)
    .inState(OrderState.DRAFT)
        .withTool(addItemTool)
        .withTool(removeItemTool)
        .withTool(confirmTool).transitionsTo(OrderState.CONFIRMED)
    .inState(OrderState.CONFIRMED)
        .withTool(shipTool).transitionsTo(OrderState.SHIPPED)
    .inState(OrderState.SHIPPED)
        .withTool(deliverTool).transitionsTo(OrderState.DELIVERED)
    .inState(OrderState.DELIVERED)
        .withTool(reviewTool)
        .build()
    .build();
```

### Dynamic Starting State

Start the tool in a different state at runtime:

```java
// Resume an order that's already confirmed
var resumedTool = stateMachine.startingIn(OrderState.CONFIRMED);
```

### Global Tools

Tools available in all states:

```java
.withGlobalTools(helpTool, statusTool)
```

Or add one at a time:

```java
.withGlobalTool(helpTool)
```

### Configuration

```java
var stateMachine = new StateMachineTool<>("processor", "Process items", State.class)
    .withInitialState(State.READY)
    .withLlm(new LlmOptions().withTemperature(0.7))
    .withMaxIterations(15)
    .withSystemPromptCreator((process, state) ->
        "You are processing in state: " + state);
```

## Kotlin Usage

```kotlin
enum class OrderState { DRAFT, CONFIRMED, SHIPPED, DELIVERED }

StateMachineTool("orderProcessor", "Process orders", OrderState::class.java)
    .withInitialState(OrderState.DRAFT)
    .inState(OrderState.DRAFT)
        .withTool(addItemTool)
        .withTool(confirmTool).transitionsTo(OrderState.CONFIRMED)
    .inState(OrderState.CONFIRMED)
        .withTool(shipTool).transitionsTo(OrderState.SHIPPED)
    .build()
    .build()
```

## How It Works

1. States are defined by an enum provided at construction
2. Tools are registered with specific states where they're available
3. When called in the correct state, tools execute normally
4. When called in the wrong state, tools return an informative error message
5. On successful execution, tools can optionally transition to a new state
6. Global tools are available in all states
7. Tool descriptions include state availability and transition info

## State Transitions

```
DRAFT ──[confirmTool]──> CONFIRMED ──[shipTool]──> SHIPPED ──[deliverTool]──> DELIVERED
  │                          │                        │                          │
  └─[addItemTool]            └─(no transition)        └─(no transition)          └─[reviewTool]
  └─[removeItemTool]
```

## Comparison with PlaybookTool

| Feature           | PlaybookTool           | StateMachineTool       |
|-------------------|------------------------|------------------------|
| State definition  | Implicit (conditions)  | Explicit (enum)        |
| Transitions       | Condition-based unlock | Tool-triggered         |
| Visibility        | States not visible     | Current state visible  |
| Use case          | Progressive unlock     | Explicit workflows     |

## Classes

- `StateMachineTool` - Main tool class
- `StateBuilder` - Builder for configuring tools in a state
- `StateToolRegistration` - Registration for a tool with optional transition
- `StateHolder` - Tracks current state during execution
- `StateBoundTool` - Wrapper that enforces state availability
- `GlobalStateTool` - Wrapper for tools available in all states
