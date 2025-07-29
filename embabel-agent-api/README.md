# Embabel Agent Framework

Core library for building intelligent agent applications with Spring Boot integration.

## Overview

The Embabel Agent Framework provides a library-centric approach to agent development, supporting multiple configuration methods and seamless integration with existing Spring Boot applications.

## Strategic Direction: Enhanced Configurability

The framework is undergoing transformation from profile-based to property-based configuration for enhanced library usability and developer experience.

## Configuration Architecture

### Framework Internal Properties (`embabel.framework.*`)
**File:** `agent-framework.properties`  
**Purpose:** True framework internals managed by the library

```properties
# Framework capabilities and behavior
embabel.framework.scanning.annotation=true
embabel.framework.scanning.bean=true
embabel.framework.llm-operations.timeout=30s
embabel.framework.llm-operations.retryStrategy=exponential
embabel.framework.process-id-generation.strategy=uuid
embabel.framework.test.mockMode=true
embabel.framework.ranking.maxAttempts=5
```

**Characteristics:**
- ✅ **Sensible defaults** - rarely need changing
- ✅ **Framework behavior** - internal operations
- ✅ **Library-managed** - shipped with framework
- ⚠️ **Override with caution** - can break framework assumptions

### Application Properties (`embabel.agent.*`)
**File:** Developer's `application.yml`  
**Purpose:** Business logic and deployment choices

```yaml
embabel:
  agent:
    # Application identity
    platform:
      name: my-agent-app
      description: My Custom Agent Application
    
    # UI/UX choices
    logging:
      personality: starwars
      verbosity: debug
    
    # Model provider choices
    models:
      defaultLlm: gpt-4
      defaultEmbeddingModel: text-embedding-3-small
      provider: openai
      openai:
        apiKey: ${OPENAI_API_KEY}
    
    # Business logic
    autonomy:
      goalConfidenceCutOff: 0.8
      agentConfidenceCutOff: 0.75
      maxRetries: 3
    
    # Infrastructure choices
    infrastructure:
      observability:
        zipkinEndpoint: http://prod-zipkin:9411
        tracingEnabled: true
      neo4j:
        uri: bolt://prod-cluster:7687
        username: ${NEO4J_USERNAME}
      mcp:
        servers: ["github", "docker"]
        github:
          token: ${GITHUB_TOKEN}
```

**Characteristics:**
- ✅ **Developer-controlled** - expected to be customized
- ✅ **Environment-specific** - different per deployment
- ✅ **Business decisions** - model choices, thresholds, services
- ✅ **Infrastructure bindings** - endpoints, credentials, features

## Strategic Migration from Profiles

### Current Challenge
```yaml
# ❌ Profile-based (being phased out)
spring:
  profiles:
    active: bedrock,shell,observability

# Multiple application-{profile}.yml files to maintain
application-bedrock.yml     # Mixed framework + app concerns
application-shell.yml       # Framework behavior
application-observability.yml  # Infrastructure setup
```

### Target Architecture
```yaml
# ✅ Property-based (target)
embabel:
  framework:
    # Framework internals (library defaults)
    capabilities:
      bedrockSupported: true
      shellSupported: true
      observabilitySupported: true
  
  agent:
    # Application choices (developer config)
    models:
      provider: bedrock
      bedrock:
        region: us-east-1
    infrastructure:
      observability:
        enabled: true
```

### Migration Benefits
- **🎯 Explicit configuration** - clear what features are enabled
- **🔧 Enhanced configurability** - granular control over behavior  
- **📚 Better IDE support** - auto-completion and validation
- **🏗️ Library-centric design** - suitable for embedded usage
- **🔄 Environment flexibility** - easy override with env vars
- **📖 Self-documenting** - configuration structure shows capabilities

## Property Precedence

Configuration follows Spring Boot precedence (highest to lowest):

1. **Programmatic Properties** (Highest)
   - `@TestPropertySource(properties = [...])`
   - System properties (`-Dembabel.framework.test.mockMode=false`)
   - Environment variables (`EMBABEL_FRAMEWORK_TEST_MOCKMODE=false`)

2. **Application Properties Files**
   - `application.properties`
   - `application.yml`

3. **Framework Default Files** (Lowest)
   - `agent-framework.properties`
   - `agent-bedrock-models.properties`
   - Code defaults in `AgentFrameworkProperties.kt`

## Spring Boot Integration

```kotlin
@SpringBootApplication
@EnableConfigurationProperties(AgentFrameworkProperties::class)
class MyAgentApplication

fun main(args: Array<String>) {
    runApplication<MyAgentApplication>(*args)
}
```

## Module Independence

### Core Framework (`embabel-agent-api`)
- **Prefix:** `embabel.framework.*` and `embabel.agent.*`
- **Scope:** Core agent capabilities, model providers, business logic
- **Independence:** Works standalone without shell module

### Shell Module (`embabel-agent-shell`)  
- **Prefix:** `embabel.shell.*`
- **Scope:** Interactive CLI interface and terminal services
- **Independence:** Optional dependency with separate configuration

### Autoconfigure Module (`embabel-agent-autoconfigure`)
- **Annotation-driven:** `@EnableAgentBedrock`, `@EnableAgentShell`
- **Profile activation:** Maintains profiles for annotation convenience
- **Consumer choice:** Developers choose activation method

## Configuration Examples & Templates

The framework provides ready-to-use configuration templates in `src/main/resources/application-templates/`:

### Available Templates
- **`application-development.yml`** - Development environment with debug settings
- **`application-production.yml`** - Production environment with security best practices  
- **`application-minimal.yml`** - Minimal configuration to get started
- **`application-full-featured.yml`** - Complete configuration with all available options
- **`application-personality-demo.yml`** - Personality plugin examples and usage

### Development Environment Example
```yaml
# Copy from application-templates/application-development.yml
embabel:
  framework:
    test:
      mockMode: true
  agent:
    logging:
      personality: starwars
      verbosity: debug
    models:
      provider: ollama
    infrastructure:
      neo4j:
        enabled: true
        uri: bolt://localhost:7687
  shell:
    enabled: true
    chat:
      confirmGoals: true
```

### Production Environment Example
```yaml
# Copy from application-templates/application-production.yml
embabel:
  framework:
    test:
      mockMode: false
  agent:
    platform:
      name: production-agent
    logging:
      personality: corporate
      verbosity: info
    models:
      provider: bedrock
      bedrock:
        region: us-east-1
    infrastructure:
      observability:
        enabled: true
        tracing:
          zipkinEndpoint: ${ZIPKIN_ENDPOINT}
      neo4j:
        enabled: true
        uri: ${NEO4J_URI}
        authentication:
          username: ${NEO4J_USERNAME}
          password: ${NEO4J_PASSWORD}
  shell:
    enabled: false  # No interactive shell in production
```

**Usage:** Copy the appropriate template to your `src/main/resources/application.yml` and customize for your needs.

## Phase 1: Library-Centric Transformation

Current transformation status:

- 🔄 **Property Segregation** - Framework vs application concerns
- 🔄 **Profile Migration** - Moving from profile-based to property-based activation  
- ✅ **Shell Independence** - Complete separation achieved
- 🔄 **Enhanced Configurability** - Granular property control
- 📋 **Annotation Support** - Maintained for developer convenience

### Personality Plugin Infrastructure (In Progress)

**Current State**: Profile-based personality activation
```yaml
# Current (being migrated away from)
spring:
  profiles:
    active: starwars
```

**Target State**: Property-based with plugin architecture
```yaml
# Target library-centric approach
embabel:
  agent:
    logging:
      personality: starwars
      verbosity: debug
      enableRuntimeSwitching: true
```

**Plugin Architecture Features**:
- ✅ **Property-based activation** - No Spring profile dependencies
- 🔄 **Dynamic discovery** - Auto-find personality providers
- 🔄 **Runtime switching** - Change personalities without restart
- 🔄 **Plugin interface** - Clean provider contract for extensions

**Implementation**: 
- **Detailed Steps**: [ITERATIVE_PLAN.md](ITERATIVE_PLAN.md) 
- **Profile-Specific Changes**: [PROFILES_MIGRATION_GUIDE.md - Personality Profiles](PROFILES_MIGRATION_GUIDE.md#0-personality-profiles-migration)

## Migration from Profiles

For detailed migration instructions from profile-based configuration (`application-{profile}.yml`) to property-based configuration, see [PROFILES_MIGRATION_GUIDE.md](PROFILES_MIGRATION_GUIDE.md).

### Next Phase Goals
- **Complete profile departure** from core framework configuration
- **Plugin architecture expansion** for personalities and model providers
- **Enhanced property dynamism** matching profile flexibility
- **Developer experience improvements** with better IDE support

--------------------
(c) Embabel Software Inc 2024-2025.