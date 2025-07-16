# Embabel Agent Framework

Core library for building intelligent agent applications with Spring Boot integration.

## Overview

The Embabel Agent Framework provides a library-centric approach to agent development, supporting multiple configuration methods and seamless integration with existing Spring Boot applications.

## Configuration Approach - Planned

The framework supports flexible configuration with clear precedence (highest to lowest priority):

1. **Programmatic Properties** (Highest Priority)
   - AgentFrameworkProperties (for testing @TestPropertySource(properties = [...]))
   - System properties (`-Dembabel.framework.logging.personality=starwars`)
   - Environment variables (`EMBABEL_FRAMEWORK_LOGGING_PERSONALITY=starwars`)

2. **Properties Files**
   - `application.properties`
   - `application-{profile}.properties`

3. **YAML Files** (Lowest Priority)
   - `application.yaml` (existing files work as-is with property semantics)
   - `application-docker.yaml`, `application-neo.yaml` (backward compatible)
   - `application-{profile}.yaml`

## Configuration Properties

### Framework Internal Properties (`embabel.framework.*`)

True framework internals managed by the framework itself:

```properties
# Framework scanning and registration
embabel.framework.scanning.basePackages=com.embabel.agent
embabel.framework.scanning.componentFilters=enabled

# LLM operations internals
embabel.framework.llm-operations.timeout=30s
embabel.framework.llm-operations.retryStrategy=exponential

# Process ID generation
embabel.framework.process-id-generation.strategy=uuid

# Server-sent events configuration
embabel.framework.sse.heartbeatInterval=30s

# Logging infrastructure
embabel.framework.logging.verbosity=normal

# Test framework behavior
embabel.framework.test.mockMode=true
embabel.framework.test.simulateFailures=false
```

### Application Configuration Properties

Application developers manage these in their own configuration:

```properties
# Model provider selection (application choice)
ai.models.provider=openai
ai.models.openai.model=gpt-4
ai.models.openai.apiKey=${OPENAI_API_KEY}

# Feature toggles (application deployment choice)
app.features.a2a.enabled=true
app.features.observability.enabled=true

# Business logic configuration (application policy)
app.autonomy.goalConfidenceCutOff=0.7
app.autonomy.maxRetries=3
app.autonomy.timeout=30m
```

### Personality Configuration (Framework Exception)

Personalities are a cross-cutting concern affecting both core framework (logging colors) and shell module (terminal display). They are treated as a framework exception:

**Current (Transitional):**
```properties
# Temporary framework property during migration
embabel.framework.logging.personality=starwars
```

**Roadmap:**
1. **Phase 1**: Implement builder pattern for custom personalities
2. **Phase 2**: Move to static palette definitions in `palettes.config`
3. **Phase 3**: Programmatic fallback for backward compatibility

```kotlin
// Future: Builder pattern for custom personalities
@Bean
fun corporatePersonality(): PersonalityProvider {
    return PersonalityBuilder()
        .name("corporate")
        .palette(CorporatePalette())
        .displayTheme(CorporateDisplay())
        .build()
}
```

## Migration from Profile-Based Configuration

### Existing YAML Files
Your existing YAML configuration files continue to work unchanged:

```yaml
# application-docker.yaml (NO CHANGES NEEDED)
spring:
  profiles:
    active: docker

embabel:
  agent:
    logging:
      personality: starwars
    models:
      provider: openai
```

### Optional Import Pattern
For new YAML files, you can optionally use import directives:

```yaml
# application-myapp.yaml
spring:
  config:
    import: 
      - optional:classpath:embabel-agent-starwars.properties
      - optional:file:.env[.properties]

# Application-specific configuration
server:
  port: 8080
```

## Spring Boot Integration

Add the framework as a dependency and enable configuration properties:

```kotlin
@SpringBootApplication
@EnableConfigurationProperties(AgentFrameworkProperties::class)  // Enable framework properties
class MyAgentApplication

fun main(args: Array<String>) {
    runApplication<MyAgentApplication>(*args)
}
```

## Shell Module Independence

The shell functionality is completely encapsulated in the `embabel-agent-shell` module with its own `embabel.shell.*` configuration namespace. The core framework has no shell dependencies.

## Phase 1: Library-Centric Transformation

This framework is currently in Phase 1 of transformation from application-centric to library-centric design:

- ✅ **Dual Configuration Support** - Properties and existing YAML profiles work together
- ✅ **Shell Module Encapsulation** - Complete separation of shell functionality  
- ✅ **Backward Compatibility** - Existing configurations continue working
- ✅ **Test Profile Safety** - Automatic mock providers in test environments
- 🔄 **Phase 2 Planned** - Enhanced auto-configuration and profile deprecation

--------------------
(c) Embabel Software Inc 2024-2025.