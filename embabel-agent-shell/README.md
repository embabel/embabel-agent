# Embabel Agent Shell Module

Interactive Spring Shell experience for the Embabel Agent platform.

## Overview
This module provides a terminal-based interface for interacting with the Embabel Agent platform, built on Spring Shell framework. It offers interactive commands for agent management, chat sessions, task execution, and system operations.

## Key Components

### Shell Commands (`ShellCommands.kt`)
Main entry point providing the following commands:

| Command | Aliases | Description |
|---------|---------|-------------|
| `agents` | | List all available agents |
| `actions` | | List all available actions |
| `goals` | | List all available goals |
| `conditions` | | List all available conditions |
| `tools` | | List available tool groups |
| `toolStats` | | Show tool usage statistics |
| `models` | | List available LLM models |
| `platform` | | Show AgentPlatform information |
| `execute` | `x` | Execute a task with given intent |
| `chat` | | Start an interactive chat session |
| `chooseGoal` | | Try to choose a goal for a given intent |
| `blackboard` | `bb` | Show the last blackboard state |
| `runs` | | Show recent agent process runs |
| `clear` | | Clear the blackboard |
| `profiles` | | List active Spring profiles |
| `showOptions` | | Show current execution options |
| `setOptions` | | Configure execution options |
| `exit` | `quit`, `bye` | Exit the application |

### Terminal Services (`TerminalServices.kt`)
Provides terminal interaction capabilities:
- **Chat Session Management**: Run interactive chat sessions with agents
- **Form Handling**: Process form inputs (TextField, Button controls)
- **Confirmation Dialogs**: Handle user confirmations for operations
- **Output Channel**: `TerminalOutputChannel` for agent communication
- **Log Redirection**: Redirect logs to file during chat sessions
- **Goal Approval**: Implements `GoalChoiceApprover` for human-in-the-loop approval

### Prompt Providers
- **DefaultPromptProvider**: Basic "embabel>" prompt
- **MessageGeneratorPromptProvider**: Dynamic prompts from random messages

### Personality Providers
Theme-based prompt providers for a personalized shell experience:
- **StarWars** (`starwars/StarwarsPromptProvider.kt`)
- **Severance** (`severance/SeverancePromptProvider.kt`)
- **Hitchhiker** (`hitchhiker/HitchhikerPromptProvider.kt`)
- **Colossus** (`colossus/ColossusPromptProvider.kt`)
- **MontyPython** (`montypython/MontyPythonPromptProvider.kt`)

### Utility Functions
- **formatProcessOutput.kt**: Formats agent process output for console display, including usage and cost information
- **markdown.kt**: Converts Markdown to ANSI-styled console output (bold, italic, code blocks, headers, links, etc.)

## Configuration

### ShellConfiguration
Primary Spring configuration that:
- Enables `ShellProperties` configuration properties
- Provides a fallback `DefaultPromptProvider` when no custom provider is configured

### ShellProperties
Configuration properties under `embabel.agent.shell`:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lineLength` | Int | 140 | Maximum line length for text wrapping |
| `redirectLogToFile` | Boolean | false | Redirect log output to file during chat sessions |

## Usage
Interactive shell commands are available when running the application in shell mode. None of these classes are intended for use outside of the shell context.

### Example Commands
```bash
# List all agents
embabel> agents

# Execute a task
embabel> x "Find news about technology" -p

# Start a chat session
embabel> chat

# Show execution options
embabel> showOptions
```
