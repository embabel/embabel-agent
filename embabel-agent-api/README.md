# Embabel Agent Framework

Core library for building intelligent agent applications with Spring Boot integration.

## Overview

The Embabel Agent Framework provides a library-centric approach to agent development, supporting multiple configuration methods and seamless integration with existing Spring Boot applications.

## Strategic Direction: Enhanced Configurability

The framework is undergoing transformation from profile-based to property-based configuration for enhanced library usability and developer experience.

## Configuration Architecture

### **Property Segregation Principle**

The framework separates configuration into two distinct categories based on **who controls** and **how often** properties change:

#### **Platform Properties (`embabel.agent.platform.*`)**
**Definition:** Internal framework behavior that library controls

| Criteria | Platform Properties | Example |
|----------|-------------------|---------|
| **Ownership** | Library manages defaults | `embabel.agent.platform.scanning.annotation=true` |
| **Change Frequency** | Rarely customized | `embabel.agent.platform.ranking.max-attempts=5` |
| **Purpose** | How framework works internally | `embabel.agent.platform.llm-operations.backoff-millis=5000` |
| **Risk Level** | Can break platform assumptions | `embabel.agent.platform.models.anthropic.retry-multiplier=2.0` |
| **Defaults** | Shipped with library | In `agent-platform.properties` |

#### **Application Properties (`embabel.agent.*`)**  
**Definition:** Business decisions and deployment choices that developer controls

| Criteria | Application Properties | Example |
|----------|----------------------|---------|
| **Ownership** | Developer customizes | `embabel.agent.models.provider=openai` |
| **Change Frequency** | Expected to be modified | `embabel.agent.logging.personality=starwars` |
| **Purpose** | What application wants to do | `embabel.agent.infrastructure.neo4j.enabled=true` |
| **Risk Level** | Safe to change | `embabel.agent.models.openai.model=gpt-4` |
| **Defaults** | In developer's `application.yml` | User-specific values |

### Platform Internal Properties (`embabel.agent.platform.*`)
**File:** `agent-platform.properties`  
**Purpose:** Platform internals managed by the library

```properties
# Platform capabilities and behavior  
embabel.agent.platform.scanning.annotation=true
embabel.agent.platform.scanning.bean=true
embabel.agent.platform.llm-operations.data-binding.max-attempts=10
embabel.agent.platform.llm-operations.prompts.generate-examples-by-default=true
embabel.agent.platform.process-id-generation.include-version=false
embabel.agent.platform.ranking.max-attempts=5
embabel.agent.platform.ranking.backoff-millis=100
embabel.agent.platform.autonomy.agent-confidence-cut-off=0.6
embabel.agent.platform.sse.max-buffer-size=100
embabel.agent.platform.models.anthropic.max-attempts=10
embabel.agent.platform.test.mock-mode=true
```

**Characteristics:**
- ✅ **Sensible defaults** - rarely need changing
- ✅ **Platform behavior** - internal operations  
- ✅ **Library-managed** - shipped with library
- ⚠️ **Override with caution** - can break platform assumptions

### Application Properties (`embabel.agent.*`)
**File:** Developer's `application.yml`  
**Purpose:** Business logic and deployment choices

```yaml
embabel:
  agent:
    # UI/UX choices
    logging:
      personality: starwars
      verbosity: debug
    
    # Model provider choices
    models:
      provider: openai
      openai:
        apiKey: ${OPENAI_API_KEY}
        model: gpt-4
    
    # Infrastructure choices
    infrastructure:
      observability:
        enabled: true
        zipkinEndpoint: ${ZIPKIN_ENDPOINT}
        tracingEnabled: true
      neo4j:
        enabled: true
        uri: ${NEO4J_URI}
        authentication:
          username: ${NEO4J_USERNAME}
          password: ${NEO4J_PASSWORD}
      mcp:
        enabled: true
        servers:
          github:
            command: docker
            args: ["run", "-i", "--rm", "-e", "GITHUB_PERSONAL_ACCESS_TOKEN", "mcp/github"]
            env:
              GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_PERSONAL_ACCESS_TOKEN}
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
  agent:
    platform:
      # Platform internals (library defaults)
      scanning:
        annotation: true
      test:
        mockMode: false
    
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

3. **Platform Default Files** (Lowest)
   - `agent-platform.properties`
   - Code defaults in `AgentPlatformProperties.kt`

## Spring Boot Integration

```kotlin
@SpringBootApplication
@EnableConfigurationProperties(AgentPlatformProperties::class)
class MyAgentApplication

fun main(args: Array<String>) {
    runApplication<MyAgentApplication>(*args)
}
```

## Module Independence

### Core Framework (`embabel-agent-api`)
- **Prefix:** `embabel.agent.platform.*` and `embabel.agent.*`
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

The framework provides **granular, composable templates** in `src/main/resources/application-templates/`:

### Template Structure
```
application-templates/
├── base/                        # Granular building blocks
│   ├── platform-defaults.yml   # Platform internal settings
│   ├── logging-starwars.yml     # StarWars personality configuration
│   ├── models-openai.yml        # OpenAI model configuration
│   └── infrastructure-neo4j.yml # Neo4j infrastructure configuration
├── environments/               # Environment-specific overrides
│   ├── development-overrides.yml
│   └── production-overrides.yml
└── composed/                   # Example compositions using imports
    ├── application-development.yml
    ├── application-production.yml
    └── application-minimal.yml
```

### Import-Based Composition
Templates are designed for **composition using Spring Boot imports**, not copy-paste.

### Development Environment Example
```yaml
# application.yml.unused - Import-based composition
spring:
  config:
    import:
      - classpath:application-templates/base/platform-defaults.yml
      - classpath:application-templates/base/logging-starwars.yml
      - classpath:application-templates/base/models-openai.yml
      - classpath:application-templates/base/infrastructure-neo4j.yml
      - classpath:application-templates/environments/development-overrides.yml

# Application-specific configuration
server:
  port: 8080
```

### Production Environment Example
```yaml
# application.yml.unused - Import-based composition
spring:
  config:
    import:
      - classpath:application-templates/base/platform-defaults.yml
      - classpath:application-templates/base/logging-severance.yml
      - classpath:application-templates/base/models-bedrock.yml
      - classpath:application-templates/base/infrastructure-neo4j.yml
      - classpath:application-templates/base/infrastructure-observability.yml
      - classpath:application-templates/environments/production-overrides.yml

# Application-specific configuration
server:
  port: 8443
```

### Custom Composition Example
```yaml
# application.yml.unused - Mix and match as needed
spring:
  config:
    import:
      - classpath:application-templates/base/logging-starwars.yml
      - classpath:application-templates/base/models-anthropic.yml  # Custom mix
      - classpath:shell-templates/base/shell-enabled.yml          # From shell module

# Your application configuration
myapp:
  custom:
    setting: value
```

**Usage:** Compose exactly what you need using Spring Boot's `spring.config.import` feature.

## Property Migration (v1.x → v2.0)

### **Platform Property Namespace Consolidation**

The framework consolidates internal platform properties under unified namespaces for better organization and clarity:

| Old Property Namespace | New Property Namespace | Purpose |
|------------------------|------------------------|---------|
| `embabel.agent-platform.*` | `embabel.agent.platform.*` | Agent scanning, ranking settings |
| `embabel.autonomy.*` | `embabel.agent.platform.autonomy.*` | Autonomy confidence thresholds |
| `embabel.process-id-generation.*` | `embabel.agent.platform.process-id-generation.*` | Process ID generation settings |
| `embabel.llm-operations.*` | `embabel.agent.platform.llm-operations.*` | LLM operation retry and prompt settings |
| `embabel.sse.*` | `embabel.agent.platform.sse.*` | Server-sent events configuration |
| `embabel.anthropic.*` | `embabel.agent.platform.models.anthropic.*` | Anthropic provider retry settings |

### **Action Required**

**If you customized any platform properties**, update your `application.yml`:

```yaml
# OLD - Will be ignored
embabel:
  agent-platform:
    ranking:
      max-attempts: 10
  autonomy:
    agent-confidence-cut-off: 0.8

# NEW - Required  
embabel:
  agent:
    platform:
      ranking:
        max-attempts: 10
      autonomy:
        agent-confidence-cut-off: 0.8
```

### **Migration Detection**

The framework provides automatic detection of deprecated property usage with production-safe defaults:

```yaml
# Migration system is DISABLED by default for zero production impact
# Enable only when you need migration guidance:
embabel:
  agent:
    platform:
      migration:
        scanning:
          enabled: true                    # Activates comprehensive migration detection
          include-packages:
            - "com.yourcompany"            # Scan your packages for deprecated usage
            - "com.yourthirdparty"         # Include third-party integration packages
        warnings:
          enabled: true                    # Automatically enabled when scanning is on
          individual-logging: true         # Log each deprecated property individually
```

**Key Features:**
- ✅ **Production Safe**: Completely disabled by default (zero overhead)
- ✅ **Comprehensive**: Detects both `@ConditionalOnProperty` and `@ConfigurationProperties` deprecated usage
- ✅ **Environment Variables**: Automatically detects deprecated properties from any source
- ✅ **Verbose Feedback**: Individual warnings plus aggregated summary

**Full Migration Guide**: [PROFILES_MIGRATION_GUIDE.md - Property Namespace Migration](PROFILES_MIGRATION_GUIDE.md#phase-0-platform-property-consolidation)

### **Spring Boot + Kotlin Configuration Patterns**

The framework implements production-validated Spring Boot + Kotlin configuration patterns:

#### **Val vs Var Decision Matrix:**
| Configuration Pattern | Property Type | Recommendation | Reason |
|----------------------|--------------|----------------|--------|
| **Pure `@ConfigurationProperties`** | Scalar (String, Boolean, Int) | ✅ `val` | Constructor binding works perfectly |
| **`@Configuration` + `@ConfigurationProperties`** | Scalar | ⚠️ `var` | CGLIB proxy requires setters |
| **Any Pattern** | Complex (List, Map) | ✅ `var` | Environment variable binding needs setters |

#### **Production Lesson Learned:**
```kotlin
@Configuration  // 🚨 This annotation forces var usage
@ConfigurationProperties("app.config")
data class MyConfig(
    var enabled: Boolean = false,     // ✅ var required for CGLIB proxying
    var servers: List<String> = emptyList()  // ✅ var required for complex types
)

// vs.

@ConfigurationProperties("app.simple") // 🎯 No @Configuration
data class SimpleConfig(
    val enabled: Boolean = false      // ✅ val works with constructor binding
)
```

**Reference**: See comprehensive analysis in `AgentPlatformPropertiesIntegrationTest`

## Phase 1: Library-Centric Transformation

Current transformation status:

- ✅ **Property Segregation** - Platform vs application concerns clearly defined
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