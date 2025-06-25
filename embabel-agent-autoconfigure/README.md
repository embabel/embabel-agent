# Embabel Agent Auto-Configuration

## Objective
Bootstrap beans defined in @**Configuration** artifacts for Spring Boot Application through annotation-driven profile activation.

## How It Works

The auto-configuration uses Spring profiles to conditionally load different agent configurations. The `EmbabelEnvironmentPostProcessor` runs early in the Spring Boot startup sequence to activate profiles based on annotations found on your application class.

## Auto-Configuration Types

1. **AgentPlatformAutoConfiguration** - bootstraps Agent Platform Configuration, Tools Group Configuration, and RAG Service Configuration

## Direct Usage of Auto-Configuration

Required dependency:
```xml
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-starter-platform</artifactId>
</dependency>
```

Example:
```java
@SpringBootApplication
@ImportAutoConfiguration(AgentPlatformAutoConfiguration.class)
public class AgentExampleApplication {
    // ...
}
```

## Annotation-Driven Auto-Configuration (Recommended)

The preferred approach uses annotations that automatically activate the necessary Spring profiles:

### Available Annotations

1. **@EnableAgentShell** - Activates "shell" profile for command-line agent applications
2. **@EnableAgentMCP** - Activates "mcp-server" profile for MCP server applications
3. **@EnableAgentBedrock** - Activates "bedrock" profile for AWS Bedrock agent applications
4. **@EnableAgents** - Generic annotation for fine-grained control over profiles

### Basic Examples

```java
@SpringBootApplication
@EnableAgentShell
public class ShellAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShellAgentApplication.class, args);
    }
}
```

### Advanced Configuration with @EnableAgents

The `@EnableAgents` annotation provides additional configuration options:

```java
import com.embabel.agent.config.annotation.LocalModels;
import com.embabel.agent.config.annotation.LoggingThemes;
import com.embabel.agent.config.annotation.McpServers;

@SpringBootApplication
@EnableAgents(
        loggingTheme = LoggingThemes.STAR_WARS,         // Activates "starwars" profile
        localModels =  {LocalModels.OLLAMA},            // Activates "ollama" profile
        mcpServers =   {McpServers.DOCKER_DESKTOP}      // Activates "filesystem" profile
)
public class CustomAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustomAgentApplication.class, args);
    }
}
```

### Combining Platform and Agent Annotations

```java
import com.embabel.agent.config.annotation.LocalModels;
import com.embabel.agent.config.annotation.LoggingThemes;
import com.embabel.agent.config.annotation.McpServers;

@SpringBootApplication
@EnableAgentShell                    // Activates "shell" profile
@EnableAgents(
        loggingTheme = LoggingThemes.STAR_WARS,         // Activates "starwars" profile
        localModels = {LocalModels.OLLAMA},             // Activates "ollama" and "llamacpp" profiles
        mcpServers = {McpServers.DOCKER_DESKTOP}        // Activates "docker" and "github" profiles
)
public class AdvancedAgentApplication { 
    public static void main(String[] args) {
        SpringApplication.run(AdvancedAgentApplication.class, args);
    }
}
```

## Profile Activation Order

The `EmbabelEnvironmentPostProcessor` activates profiles in the following order:

1. **Platform Profiles** - From `@AgentPlatform` or meta-annotations like `@EnableAgentShell`
2. **Logging Theme** - From `@EnableAgents(loggingTheme="...")`
3. **Local Models** - From `@EnableAgents(localModels={...})`
4. **MCP Servers** - From `@EnableAgents(mcpServers={...})`

## Available Logging Themes

When using `@EnableAgents`, you can specify a logging theme that changes the appearance of log output:

- `starwars` - Star Wars themed logging
- `severance` - Severance TV show themed logging
- Custom themes can be added by creating corresponding profile configurations

## Implementation Details

- The `EmbabelEnvironmentPostProcessor` runs with `HIGHEST_PRECEDENCE` to ensure profiles are activated before any beans are created
- Profiles are activated through both system properties (`spring.profiles.active`) and the Spring Environment API
- Existing profiles are preserved when new profiles are added
- The processor handles multiple application sources, though typically there's only one main class

## Notes

1. **Multiple platform annotations** - You can use multiple platform annotations (`@EnableAgentShell`, `@EnableAgentMCP`, etc.) on the same application class
2. **Annotation-driven approach is preferred** - We favor annotation-driven auto-configuration over direct `@ImportAutoConfiguration` usage for better flexibility and cleaner code
3. **Profile-based configuration** - All configurations are profile-based, allowing easy switching between different agent modes
4. **Empty annotations** - Using `@EnableAgents` with empty attributes (e.g., `loggingTheme = ""`) will not activate any profiles for those attributes