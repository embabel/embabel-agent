# Embabel Agent Shell Starter

A Spring Boot starter that enables interactive command-line shell functionality for Embabel Agent applications.

## Overview

This starter automatically configures your Embabel Agent application to operate in shell mode using Spring Boot auto-configuration. Simply add the dependency and use `@SpringBootApplication` — no additional annotations required.

## Features

- **Spring Boot Auto-Configuration**: Works automatically when the starter is on the classpath
- **Interactive Command-Line Interface**: Full Spring Shell integration with command history
- **Agent Management**: List, inspect, and execute agents from the command line
- **Chat Mode**: Interactive chat sessions with configurable personas
- **Logging Personalities**: Fun themed logging (Star Wars, Severance, Hitchhiker, Monty Python, Colossus)
- **Human-in-the-Loop**: Form filling and confirmation prompts in the terminal
- **Process Tracking**: View execution history, blackboard state, and cost information

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-starter-shell</artifactId>
</dependency>
```

### 2. Create Application

```kotlin
@SpringBootApplication
class MyAgentShellApplication

fun main(args: Array<String>) {
    runApplication<MyAgentShellApplication>(*args)
}
```

```java
// Java version
@SpringBootApplication
public class MyAgentShellApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyAgentShellApplication.class, args);
    }
}
```

### 3. Run Your Application

```bash
./mvnw spring-boot:run
```

Your application will start in interactive shell mode.

## Shell Commands

| Command | Alias | Description |
|---------|-------|-------------|
| `execute <intent>` | `x` | Execute a task. Put the task in double quotes. |
| `chat` | | Start an interactive chat session |
| `agents` | | List all registered agents |
| `actions` | | List all available actions |
| `goals` | | List all achievable goals |
| `conditions` | | List all conditions |
| `tools` | | List available tool groups |
| `toolStats` | | Show tool usage statistics |
| `models` | | List available LLM models |
| `chooseGoal <intent>` | | Show goal rankings for an intent |
| `blackboard` | `bb` | Show the last blackboard state |
| `runs` | | Show recent agent process execution history |
| `clear` | | Clear the blackboard |
| `platform` | | Show AgentPlatform information |
| `profiles` | | List active Spring profiles |
| `showOptions` | | Show current execution options |
| `setOptions` | | Configure execution options |
| `exit` | `quit`, `bye` | Exit the application |

### Execute Command Options

The `execute` (or `x`) command supports several flags:

```bash
x "Find horoscope news for Alice who is a Gemini" -p -d
```

| Flag | Long Form | Description |
|------|-----------|-------------|
| `-o` | `--open` | Run in open mode (choose goal dynamically) |
| `-p` | `--showPrompts` | Show prompts sent to LLMs |
| `-r` | `--showResponses` | Show LLM responses |
| `-d` | `--debug` | Show debug information |
| `-s` | `--state` | Use existing blackboard state |
| `-td` | `--toolDelay` | Add delay between tool calls |
| `-od` | `--operationDelay` | Add delay between operations |
| `-P` | `--showPlanning` | Show detailed planning info (default: true) |

## Configuration

### Shell Properties

Configure shell behavior using the `embabel.agent.shell` prefix:

```yaml
embabel:
  agent:
    shell:
      line-length: 140           # Terminal line width for wrapping
      redirect-log-to-file: false # Redirect logs to file during chat
      web-application-type: none  # Prevent web server startup
      command:
        exit-enabled: false       # Enable built-in 'exit' command
        quit-enabled: false       # Enable built-in 'quit' command
      interactive:
        enabled: true             # Enable interactive mode
        history-enabled: true     # Enable command history
```

### Logging Personality

Set the logging personality via property:

```yaml
embabel:
  agent:
    logging:
      personality: starwars  # Options: starwars, severance, hitchhiker, montypython, colossus
```

Or programmatically:

```kotlin
fun main(args: Array<String>) {
    runApplication<MyAgentShellApplication>(*args) {
        setDefaultProperties(
            mapOf("embabel.agent.logging.personality" to LoggingThemes.STAR_WARS)
        )
    }
}
```

## Configuration Properties Reference

| Property | Default | Description |
|----------|---------|-------------|
| `embabel.agent.shell.line-length` | `140` | Terminal width for text wrapping |
| `embabel.agent.shell.redirect-log-to-file` | `false` | Redirect logs during chat sessions |
| `embabel.agent.shell.web-application-type` | `none` | Spring Boot web application type |
| `embabel.agent.shell.command.exit-enabled` | `false` | Enable Spring Shell 'exit' command |
| `embabel.agent.shell.command.quit-enabled` | `false` | Enable Spring Shell 'quit' command |
| `embabel.agent.shell.interactive.enabled` | `true` | Enable interactive shell mode |
| `embabel.agent.shell.interactive.history-enabled` | `true` | Enable command history navigation |

## How It Works

1. **Auto-Configuration**: `AgentShellAutoConfiguration` activates when the starter is present
2. **Component Scanning**: Shell commands and services are discovered via `@ComponentScan`
3. **Spring Shell Integration**: Commands are registered as `@ShellComponent` beans
4. **Agent Platform Access**: Shell commands interact with the `Autonomy` and `AgentPlatform` APIs

## Architecture

```
embabel-agent-starter-shell
         │
         ├── embabel-agent-starter (core agent platform)
         │
         └── embabel-agent-shell-autoconfigure
                    │
                    └── AgentShellAutoConfiguration
                              │
                              └── @ComponentScan("com.embabel.agent.shell")
                                        │
                                        ├── ShellCommands (main commands)
                                        ├── TerminalServices (I/O handling)
                                        ├── ShellConfiguration (prompt provider)
                                        └── Personality providers (themed logging)
```

### Key Components

- **`ShellCommands`**: Main shell command implementations
- **`TerminalServices`**: Terminal I/O, form handling, chat sessions, confirmation prompts
- **`ShellConfiguration`**: Shell-specific Spring configuration
- **`ShellProperties`**: Configuration properties for shell behavior
- **Personality Providers**: Themed prompt providers (Star Wars, Severance, etc.)

## Chat Mode

Start an interactive chat session:

```bash
shell:> chat
Chat session abc123 started. Type 'exit' to end the session.
Type /help for available commands.
You: Hello!
Assistant: Hi there! How can I help you today?
You: exit
Conversation finished
```

During chat, logs can optionally be redirected to a file to keep the terminal clean.

## Human-in-the-Loop Support

The shell provides interactive support for:

- **Confirmation Prompts**: Y/N confirmations for agent decisions
- **Form Filling**: Text field input with validation
- **Goal Approval**: Approve or reject goal selections

## Example Session

```bash
$ ./mvnw spring-boot:run

  ╔═══════════════════════════════════════════════════════════╗
  ║                   Embabel Agent Shell                      ║
  ╚═══════════════════════════════════════════════════════════╝

shell:> agents
Agents:
─────────────────────────────────────────
StarNewsFinder: Find news based on a person's star sign
Researcher: Research a topic using multiple LLMs
...

shell:> x "Find horoscope news for Alice who is a Gemini"
[Executing StarNewsFinder...]
...

shell:> bb
Blackboard contents:
  userInput: UserInput(text="Find horoscope news for Alice who is a Gemini")
  person: Person(name="Alice")
  starSign: StarSign.GEMINI
  ...

shell:> exit
Goodbye!
```

## Dependencies

This starter automatically includes:

- `embabel-agent-starter` - Core agent platform
- `embabel-agent-shell-autoconfigure` - Auto-configuration
- Spring Shell Starter
- JLine terminal library

## License

Licensed under the Apache License, Version 2.0.
