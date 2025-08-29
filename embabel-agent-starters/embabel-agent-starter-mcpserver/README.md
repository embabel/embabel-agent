# Embabel Agent MCP Server Starter

A Spring Boot starter that enables Model Context Protocol (MCP) server functionality for Embabel Agent applications.

## Overview

This starter automatically configures your Embabel Agent application to operate as an MCP server when the `@EnableAgentMcpServer` annotation is detected. It enables MCP server auto-configuration, allowing AI models and other MCP clients to interact with your agent's capabilities through the standardized Model Context Protocol.

## Features

- **Automatic MCP Server Detection**: Activates when `@EnableAgentMcpServer` annotation is present
- **Auto-Configuration Enablement**: Sets `embabel.agent.mcpserver.enabled=true` to trigger MCP server setup
- **Early Environment Processing**: Configures MCP server mode before application context initialization
- **Minimal Bootstrap**: Focused on enablement, delegates detailed configuration to auto-configuration classes

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-starter-mcpserver</artifactId>
    <version>${embabel.version}</version>
</dependency>
```

### 2. Enable MCP Server Mode

```java
@SpringBootApplication
@EnableAgentMcpServer
public class MyMcpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyMcpServerApplication.class, args);
    }
}
```

### 3. Run Your MCP Server

```bash
./mvnw spring-boot:run
```

Your application will start with MCP server functionality enabled and ready to accept client connections.

## Configuration

The starter automatically sets `embabel.agent.mcpserver.enabled=true` when the annotation is detected. Additional MCP server configuration is handled by the main MCP server auto-configuration:

### application.yml
```yaml
embabel:
  agent:
    mcpserver:
      # enabled: true  # Automatically set by this starter
      transport:
        type: stdio
        port: 8080
      protocol:
        version: "2024-11-05"
        timeout: 30000
      resources:
        auto-discovery: true
```

### application.properties
```properties
# embabel.agent.mcpserver.enabled=true  # Automatically set by this starter
embabel.agent.mcpserver.transport.type=stdio
embabel.agent.mcpserver.transport.port=8080
embabel.agent.mcpserver.protocol.version=2024-11-05
```

## How It Works

1. **Annotation Scanning**: `McpServerEnvironmentPostProcessor` scans application sources for `@EnableAgentMcpServer`
2. **Early Processing**: Runs at `HIGHEST_PRECEDENCE + 10` to enable MCP server before other processors
3. **Property Injection**: Adds `embabel.agent.mcpserver.enabled=true` as high-priority property
4. **Auto-Configuration Trigger**: The enabled property activates MCP server auto-configuration
5. **Server Initialization**: MCP server components are configured and started

## Architecture

```
@EnableAgentMcpServer Annotation
         ↓
McpServerEnvironmentPostProcessor
         ↓
embabel.agent.mcpserver.enabled=true
         ↓
MCP Server Auto-Configuration
         ↓
MCP Server Components
```

### Key Components

- **`@EnableAgentMcpServer`**: Marker annotation to enable MCP server mode
- **`McpServerEnvironmentPostProcessor`**: Environment post-processor for early enablement
- **Auto-Configuration Classes**: Handle detailed MCP server configuration (separate from this starter)

## Conditional Activation

MCP server mode only activates when:
- The starter dependency is present
- At least one source class has `@EnableAgentMcpServer` annotation
- No explicit disabling configuration is present

## Best Practices

### Development Environment
```java
@Profile("dev")
@EnableAgentMcpServer
@SpringBootApplication
public class DevMcpServerApplication {
    // MCP server for development and testing
}
```

### Production Deployment
```java
@Profile("mcp-server")
@EnableAgentMcpServer
@SpringBootApplication  
public class ProductionMcpServerApplication {
    // Dedicated MCP server instance
}

@Profile("!mcp-server")
@SpringBootApplication
public class ProductionWebApplication {
    // Web application without MCP server
}
```

### Conditional Components
```java
@Component
@ConditionalOnProperty(name = "embabel.agent.mcpserver.enabled", havingValue = "true")
public class McpToolRegistry {
    
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        // Register tools when MCP server is enabled
        registerAgentTools();
    }
}
```

## Error Handling

The starter handles configuration scenarios gracefully:

- **Missing Annotation**: Silently skips processing when annotation is not found
- **Null Sources**: Safely handles cases where application sources are null/empty
- **Mixed Source Types**: Filters non-Class sources and processes only valid classes

## Development Integration

### IDE Configuration
Most IDEs will recognize the annotation and provide appropriate code completion and navigation for MCP server related code.

### Testing
```java
@SpringBootTest
@EnableAgentMcpServer
class McpServerIntegrationTest {
    
    @Test
    void mcpServerShouldBeEnabled() {
        // Test MCP server functionality
    }
}
```

## Troubleshooting

### MCP Server Not Starting
- Verify `@EnableAgentMcpServer` annotation is present on a configuration class
- Check that starter dependency is included in your project
- Review application logs for enablement confirmation

### Annotation Not Detected
- Ensure annotation is on a class that's part of Spring Boot's component scanning
- Verify the annotated class is included in `SpringApplication.run()` sources
- Check package structure and component scan configuration

### Property Not Set
- Confirm the starter's environment post-processor is running (check debug logs)
- Verify no other configuration is overriding the enabled property
- Check property source precedence in Spring environment

## Dependencies

This starter includes:
- Embabel Agent Starter (base functionality)
- Embabel Agent MCP Server (core MCP implementation)

The detailed MCP server configuration and components are provided by the separate `embabel-agent-mcpserver` module.

## License

Licensed under the Apache License, Version 2.0.