# Embabel Agent Shell Starter

A Spring Boot starter that enables interactive command-line shell functionality for Embabel Agent applications.

## Overview

This starter automatically configures your Embabel Agent application to operate in shell mode when the `@EnableAgentShell` annotation is detected. It prevents web server startup and configures Spring Shell for optimal command-line interaction.

## Features

- **Automatic Shell Mode Detection**: Activates when `@EnableAgentShell` annotation is present
- **Web Server Prevention**: Automatically sets `spring.main.web-application-type=none`
- **Spring Shell Configuration**: Configures interactive shell with sensible defaults
- **Flexible Configuration**: Customizable shell behavior through properties
- **Early Environment Processing**: Configures shell mode before application context initialization

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-starter-shell</artifactId>
    <version>${embabel.version}</version>
</dependency>
```

### 2. Enable Shell Mode

```java
@SpringBootApplication
@EnableAgentShell
public class MyAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyAgentApplication.class, args);
    }
}
```

### 3. Run Your Application

```bash
./mvnw spring-boot:run
```

Your application will start in interactive shell mode instead of as a web server.

## Configuration

Configure shell behavior using the `embabel.agent.shell` prefix:

### application.yml
```yaml
embabel:
  agent:
    shell:
      web-application-type: none  # none, servlet, or reactive
      command:
        exit-enabled: true        # Enable 'exit' command
        quit-enabled: true        # Enable 'quit' command
      interactive:
        enabled: true             # Enable interactive mode
        history-enabled: true     # Enable command history
```

### application.properties
```properties
embabel.agent.shell.web-application-type=none
embabel.agent.shell.command.exit-enabled=true
embabel.agent.shell.command.quit-enabled=true
embabel.agent.shell.interactive.enabled=true
embabel.agent.shell.interactive.history-enabled=true
```

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `embabel.agent.shell.web-application-type` | `none` | Spring Boot web application type (`none`, `servlet`, `reactive`) |
| `embabel.agent.shell.command.exit-enabled` | `true` | Enable the 'exit' command |
| `embabel.agent.shell.command.quit-enabled` | `true` | Enable the 'quit' command |
| `embabel.agent.shell.interactive.enabled` | `true` | Enable interactive shell mode |
| `embabel.agent.shell.interactive.history-enabled` | `true` | Enable command history navigation |

## How It Works

1. **Annotation Detection**: `ShellEnvironmentPostProcessor` scans application sources for `@EnableAgentShell`
2. **Early Configuration**: Runs at `HIGHEST_PRECEDENCE + 10` to configure environment before other processors
3. **Property Binding**: Attempts to bind configuration from `embabel.agent.shell.*` properties
4. **Fallback Handling**: Uses sensible defaults if binding fails or properties are missing
5. **Environment Modification**: Adds shell configuration as high-priority property source

## Architecture

```
@EnableAgentShell Annotation
         ↓
ShellEnvironmentPostProcessor
         ↓
AgentShellStarterProperties
         ↓
Spring Shell Configuration
         ↓
Interactive Command Line
```

### Key Components

- **`@EnableAgentShell`**: Marker annotation to enable shell mode
- **`ShellEnvironmentPostProcessor`**: Environment post-processor for early configuration
- **`AgentShellStarterProperties`**: Configuration properties with validation
- **`ShellConfiguration`**: Internal domain object for shell settings

## Error Handling

The starter handles configuration errors gracefully:

- **Missing Properties**: Uses default values if configuration binding fails
- **Invalid Values**: Validation prevents invalid `web-application-type` values
- **Null Sources**: Safely handles cases where application sources are null/empty
- **Binding Exceptions**: Logs warnings and continues with defaults

## Conditional Activation

Shell mode only activates when:
- The starter dependency is present
- At least one source class has `@EnableAgentShell` annotation
- No explicit disabling configuration is present

## Best Practices

### Development
```java
@Profile("dev")
@EnableAgentShell
@SpringBootApplication
public class DevApplication {
    // Shell mode for development
}
```

### Production
```java
@Profile("!shell")
@SpringBootApplication  
public class ProductionApplication {
    // Web mode for production
}

@Profile("shell")
@EnableAgentShell
@SpringBootApplication
public class ShellApplication {
    // Shell mode when needed
}
```

### Custom Commands
```java
@Component
@ConditionalOnProperty(name = "embabel.agent.shell.interactive.enabled", havingValue = "true")
public class MyAgentCommands {
    
    @ShellMethod("Execute agent task")
    public String execute(@ShellOption String task) {
        return "Executing: " + task;
    }
}
```

## Troubleshooting

### Shell Mode Not Activating
- Verify `@EnableAgentShell` annotation is present
- Check that starter dependency is included
- Ensure no conflicting web configuration

### Web Server Still Starting
- Confirm `web-application-type` is set to `none`
- Check property source precedence
- Verify no other configuration is overriding the setting

### Commands Not Available
- Ensure Spring Shell dependency is present
- Check component scanning includes command classes
- Verify conditional activation logic

### History Not Working
- Confirm `interactive.history-enabled=true`
- Check terminal supports ANSI escape sequences
- Verify JLine library is available

## Dependencies

This starter automatically includes:
- Spring Boot Starter
- Spring Shell Starter
- Validation API

## License

Licensed under the Apache License, Version 2.0.