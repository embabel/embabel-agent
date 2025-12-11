# Embabel Agent Framework

Core library for building intelligent agent applications with Spring Boot integration.

## Overview

The Embabel Agent Framework provides a library-centric approach to agent development, supporting multiple configuration methods and seamless integration with existing Spring Boot applications.

## Configurability

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
**File:**
```embabel-agent/embabel-agent-api/src/main/resources/agent-application.properties```

**Developer's overrides** in ```application.yml``` (example below)

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

- relies on autoconfiguration, please refer to ```embabel-agent/embabel-agent-autoconfigure```
- developers to apply dependencies on proper starter, please refer to ```embabel-agent/embabel-agent-starters```

## Module Independence

### Core Framework (`embabel-agent-api`)
- **Prefix:** `embabel.agent.platform.*` and `embabel.agent.*`
- **Scope:** Core agent capabilities, model providers, business logic
- **Independence:** Works standalone without shell module

### Shell Module (`embabel-agent-shell`)  
- **Prefix:** `embabel.agent.shell.*`
- **Scope:** Interactive CLI interface and terminal services
- **Independence:** Optional dependency with separate configuration

### Autoconfigure Module (`embabel-agent-autoconfigure`)
- **Profile activation:** Maintains theme profiles for annotation convenience
- **Consumer choice:** Developers choose activation method



**Usage:** Compose exactly what you need using starters.

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

--------------------
(c) Embabel Software Inc 2024-2025.