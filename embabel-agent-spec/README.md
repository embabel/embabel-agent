# embabel-agent-spec

Declarative YAML-based agent step definitions for the Embabel Agent framework.

## Overview

This module enables defining agent actions and goals in YAML files rather than code. Steps are loaded at runtime and converted to executable `Action` and `Goal` instances that integrate with the GOAP planning system.

## Key Components

- **StepSpec** - Base interface for step definitions (actions and goals)
- **PromptedActionSpec** - YAML-serializable action that uses LLM prompts to transform inputs to outputs
- **GoalSpec** - YAML-serializable goal definition
- **StepSpecAgentScopeBuilder** - Loads YAML specs and deploys them as an `AgentScope`
- **YmlStepSpecRepository** - Reads step definitions from YAML files in a directory

## YAML Action Format

```yaml
stepType: action
name: summarize
description: Summarize the input text
inputTypeNames:
  - UserInput
outputTypeName: Summary
prompt: "Summarize the following: {{userInput.content}}"
```

### Optional Fields

```yaml
stepType: action
name: analyze
description: Analyze data with specific settings
llm:
  temperature: 0.7
  model: gpt-4
inputTypeNames:
  - RawData
outputTypeName: Analysis
prompt: "Analyze this data: {{rawData}}"
toolGroups:
  - search
  - web
cost: 0.5
value: 0.8
canRerun: false
nullable: true
```

## YAML Goal Format

```yaml
stepType: goal
name: completed
description: Processing is complete
outputTypeName: Summary
```

## Usage

```kotlin
val builder = StepSpecAgentScopeBuilder(
    name = "my-agent",
    agentPlatform = platform,
    repository = YmlStepSpecRepository("/path/to/steps"),
)

// Deploy to platform
val scope = builder.deploy()
```

By default, `YmlStepSpecRepository` loads from `./steps` directory.

## Variable Naming

Input and output type names are automatically converted to variable names by decapitalizing the simple class name:
- `UserInput` → `userInput`
- `com.example.Summary` → `summary`

These variable names are used in prompt templates (e.g., `{{userInput.content}}`).
