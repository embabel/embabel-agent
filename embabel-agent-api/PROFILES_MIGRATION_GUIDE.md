# Profiles Migration Guide: From Profile-Based to Property-Based Configuration

## Table of Contents

### **Getting Started**
- [Overview](#overview)
- [Security Considerations](#security-considerations)
- [Migration Strategy Overview](#migration-strategy-overview)

### **Phase 0: Platform Property Foundation**
- [Platform Property Consolidation (Prerequisite)](#phase-0-platform-property-consolidation-prerequisite)
  - [Migration Detection Configuration](#migration-detection-configuration)
  - [Property Namespace Changes](#property-namespace-changes)
  - [Action Required: Update Your Platform Property Overrides](#action-required-update-your-platform-property-overrides)
  - [Environment Variable Updates](#environment-variable-updates)
  - [Migration Detection & Warnings](#migration-detection--warnings)
  - [Testing Platform Property Migration](#testing-platform-property-migration)
  - [Configuration Precedence](#configuration-precedence-highest-to-lowest)

### **Profile Migration Matrix**
- [Profile Migration Matrix](#profile-migration-matrix)

### **Individual Profile Migrations**
- [0. Personality Profiles Migration](#0-personality-profiles-migration)
- [1. Shell Profile Migration](#1-shell-profile-migration)
- [2. Neo4j Profile Migration](#2-neo4j-profile-migration)
- [3. MCP Profiles Migration (docker-ce & docker-desktop)](#3-mcp-profiles-migration-docker-ce--docker-desktop)
- [4. Observability Profile Migration](#4-observability-profile-migration)

### **Implementation Strategy**
- [Backward Compatibility Strategy](#backward-compatibility-strategy)
- [Environment Variables Best Practices](#environment-variables-best-practices)
- [Configuration Examples by Environment](#configuration-examples-by-environment)

### **Support & Maintenance**
- [Troubleshooting](#troubleshooting)
- [Migration Timeline](#migration-timeline)
- [Benefits of Property-Based Configuration](#benefits-of-property-based-configuration)

---

## Overview

This guide provides detailed step-by-step instructions for migrating from Spring profile-based configuration (`application-{profile}.yml`) to property-based configuration (`embabel.agent.*` properties) in the Embabel Agent Framework.

## Security Considerations

### ⚠️ **Critical Security Requirements**

1. **Never hardcode credentials** in configuration classes or default values
2. **Always use environment variables** for sensitive information
3. **Remove default passwords** from any configuration
4. **Validate required properties** at startup

```kotlin
// ❌ NEVER DO THIS - Security risk
data class AuthConfig(
    var username: String = "neo4j",
    var password: String = "defaultpassword"  // Security vulnerability!
)

// ✅ SECURE APPROACH
data class AuthConfig(
    var username: String = "",  // Must be provided
    var password: String = ""   // Must be provided via environment
)
```

## Migration Strategy Overview

### **Property Segregation Principle**

- **Platform Properties (`embabel.agent.platform.*`)** - Internal platform behavior, rarely changed
- **Application Properties (`embabel.agent.*`)** - Business logic, deployment choices, credentials

---

## **Phase 0: Platform Property Consolidation (Prerequisite)**

**Important**: Before migrating profiles, the framework consolidates internal platform properties under a unified namespace. This is automatic for the library but requires action if you customized platform properties.

### **Migration Detection Configuration**

The framework automatically detects deprecated property usage and issues warnings. You can configure this scanning behavior:

#### **application.properties**
```properties
# Override scanning packages (default: com.embabel.agent, com.embabel.agent.shell)
embabel.agent.platform.migration.scanning.include-packages[0]=com.embabel.agent
embabel.agent.platform.migration.scanning.include-packages[1]=com.mycorp.custom
embabel.agent.platform.migration.scanning.include-packages[2]=com.thirdparty.integration

# Add additional package excludes
embabel.agent.platform.migration.scanning.additional-excludes[0]=com.noisy.framework
embabel.agent.platform.migration.scanning.additional-excludes[1]=com.slow.scanner

# Control JAR scanning (default: true for performance)
embabel.agent.platform.migration.scanning.auto-exclude-jar-packages=false

# Enable scanning for Iteration 1+ migrations (disabled by default in Iteration 0)
embabel.agent.platform.migration.scanning.enabled=true

# Disable scanning entirely (e.g., in production)
embabel.agent.platform.migration.scanning.enabled=false
```

#### **application.yml**
```yaml
embabel:
  agent:
    platform:
      migration:
        scanning:
          include-packages:
            - com.embabel.agent
            - com.mycorp.custom
            - com.thirdparty.integration
          additional-excludes:
            - com.noisy.framework
            - com.slow.scanner
          auto-exclude-jar-packages: false
          # Enable for Iteration 1+ migrations (disabled by default in Iteration 0)
          enabled: true
```

#### **Environment Variables**
```bash
# Include packages (comma-separated)
export EMBABEL_AGENT_PLATFORM_MIGRATION_SCANNING_INCLUDE_PACKAGES=com.embabel.agent,com.mycorp.custom

# Additional excludes (comma-separated)  
export EMBABEL_AGENT_PLATFORM_MIGRATION_SCANNING_ADDITIONAL_EXCLUDES=com.noisy.framework,com.slow.scanner

# Control scanning behavior
export EMBABEL_AGENT_PLATFORM_MIGRATION_SCANNING_AUTO_EXCLUDE_JAR_PACKAGES=false

# Enable for Iteration 1+ migrations (disabled by default in Iteration 0)
export EMBABEL_AGENT_PLATFORM_MIGRATION_SCANNING_ENABLED=true

# Disable scanning entirely (e.g., in production)
export EMBABEL_AGENT_PLATFORM_MIGRATION_SCANNING_ENABLED=false
```

### **Property Namespace Changes**

These changes happen automatically in the library - **no action needed** unless you override these properties:

| Old Property Namespace | New Property Namespace | Purpose |
|------------------------|------------------------|---------|
| `embabel.agent-platform.*` | `embabel.agent.platform.*` | Agent scanning, ranking settings |
| `embabel.autonomy.*` | `embabel.agent.platform.autonomy.*` | Autonomy confidence thresholds |
| `embabel.process-id-generation.*` | `embabel.agent.platform.process-id-generation.*` | Process ID generation settings |
| `embabel.llm-operations.*` | `embabel.agent.platform.llm-operations.*` | LLM operation retry and prompt settings |
| `embabel.sse.*` | `embabel.agent.platform.sse.*` | Server-sent events configuration |
| `embabel.anthropic.*` | `embabel.agent.platform.models.anthropic.*` | Anthropic provider retry settings |

### **Action Required: Update Your Platform Property Overrides**

**If you customized any platform properties**, update your configuration:

```yaml
# OLD - Will be ignored
embabel:
  agent-platform:
    scanning:
      annotation: false
    ranking:
      max-attempts: 10
      backoff-millis: 200
  autonomy:
    agent-confidence-cut-off: 0.8
    goal-confidence-cut-off: 0.7
  process-id-generation:
    include-version: true
    include-agent-name: true
  llm-operations:
    prompts:
      maybe-prompt-template: "custom_template"
      generate-examples-by-default: false
    data-binding:
      max-attempts: 15
      fixed-backoff-millis: 50
  sse:
    max-buffer-size: 200
    max-process-buffers: 2000
  anthropic:
    max-attempts: 15
    backoff-millis: 3000

# NEW - Required
embabel:
  agent:
    platform:
      scanning:
        annotation: false
      ranking:
        max-attempts: 10
        backoff-millis: 200
      autonomy:
        agent-confidence-cut-off: 0.8
        goal-confidence-cut-off: 0.7
      process-id-generation:
        include-version: true
        include-agent-name: true
      llm-operations:
        prompts:
          maybe-prompt-template: "custom_template"
          generate-examples-by-default: false
        data-binding:
          max-attempts: 15
          fixed-backoff-millis: 50
      sse:
        max-buffer-size: 200
        max-process-buffers: 2000
      models:
        anthropic:
          max-attempts: 15
          backoff-millis: 3000
```

### **Environment Variable Updates**

Update environment variables to use new namespaces:

```bash
# OLD - Will be ignored
export EMBABEL_AGENT_PLATFORM_RANKING_MAX_ATTEMPTS=10
export EMBABEL_AUTONOMY_AGENT_CONFIDENCE_CUT_OFF=0.8
export EMBABEL_PROCESS_ID_GENERATION_INCLUDE_VERSION=true
export EMBABEL_LLM_OPERATIONS_DATA_BINDING_MAX_ATTEMPTS=15
export EMBABEL_SSE_MAX_BUFFER_SIZE=200
export EMBABEL_ANTHROPIC_MAX_ATTEMPTS=15

# NEW - Required
export EMBABEL_AGENT_PLATFORM_RANKING_MAX_ATTEMPTS=10
export EMBABEL_AGENT_PLATFORM_AUTONOMY_AGENT_CONFIDENCE_CUT_OFF=0.8
export EMBABEL_AGENT_PLATFORM_PROCESS_ID_GENERATION_INCLUDE_VERSION=true
export EMBABEL_AGENT_PLATFORM_LLM_OPERATIONS_DATA_BINDING_MAX_ATTEMPTS=15
export EMBABEL_AGENT_PLATFORM_SSE_MAX_BUFFER_SIZE=200
export EMBABEL_AGENT_PLATFORM_MODELS_ANTHROPIC_MAX_ATTEMPTS=15
```

### **Migration Detection & Warnings**

The framework includes automatic detection for deprecated property usage:

#### **1. Property Value Detection**
Automatically scans all property sources and warns about deprecated namespaces:

```
⚠️  DEPRECATED PROPERTIES DETECTED ⚠️

The following deprecated properties were found:
  - embabel.agent-platform.ranking.max-attempts → embabel.agent.platform.ranking.max-attempts
  - embabel.autonomy.agent-confidence-cut-off → embabel.agent.platform.autonomy.agent-confidence-cut-off

If you customized any of these in your application.yml/properties files,
update them to the new namespace. Library defaults will be updated automatically.
```

#### **2. Conditional Annotation Detection**
Optionally scans your code for `@ConditionalOnProperty` using deprecated property names:

```yaml
# Optional: Enable detailed scanning for your code
embabel:
  migration:
    conditional-scanning:
      enabled: true
      include-packages:
        - "com.yourcompany"  # Your package to scan for @ConditionalOnProperty usage
        - "com.yourclient"   # Additional packages to scan
```

Example warning:
```
⚠️  DEPRECATED @ConditionalOnProperty DETECTED ⚠️

Found user code using deprecated property names in conditional annotations:

Bean: MyCustomRankingConfig
Location: com.yourcompany.config.MyCustomRankingConfig
Change: embabel.agent-platform.ranking.enabled → embabel.agent.platform.ranking.enabled
```

### **Testing Platform Property Migration**

```kotlin
@TestPropertySource(properties = [
    "embabel.agent.platform.scanning.annotation=false",
    "embabel.agent.platform.ranking.max-attempts=15",
    "embabel.agent.platform.autonomy.agent-confidence-cut-off=0.9"
])
class PlatformPropertyMigrationTest {
    
    @Autowired
    private lateinit var agentPlatformProperties: AgentPlatformProperties
    
    @Test
    fun `should use new platform property namespace`() {
        assertThat(agentPlatformProperties.scanning.annotation).isFalse()
        assertThat(agentPlatformProperties.ranking.maxAttempts).isEqualTo(15)
        assertThat(agentPlatformProperties.autonomy.agentConfidenceCutOff).isEqualTo(0.9)
    }
}
```

---

### **Configuration Precedence (Highest to Lowest)**

1. **Environment Variables** - `EMBABEL_AGENT_INFRASTRUCTURE_NEO4J_URI=bolt://prod:7687`
2. **System Properties** - `-Dembabel.agent.infrastructure.neo4j.uri=bolt://test:7687`
3. **Application Properties** - `application.yml` values
4. **Framework Defaults** - Library-provided defaults (no credentials)

## Profile Migration Matrix

| Profile | Current File | Migration Complexity | Security Concerns |
|---------|--------------|---------------------|-------------------|
| `starwars` | Spring profile only | Low | None |
| `severance` | Spring profile only | Low | None |
| `hitchhiker` | Spring profile only | Low | None |
| `montypython` | Spring profile only | Low | None |
| `colossus` | Spring profile only | Low | None |
| `shell` | `application-shell.yml` | Low | None |
| `neo` | `application-neo.yml` | Low | **High** - Contains credentials |
| `docker-ce` | `application-docker-ce.yml` | Medium | **High** - API keys required |
| `docker-desktop` | `application-docker-desktop.yml` | Low | None |
| `observability` | `application-observability.yml` | Medium | Low - Endpoints only |

---

## 0. Personality Profiles Migration

### **Current State**
```yaml
# No application-{profile}.yml files - just Spring profile activation
spring:
  profiles:
    active: starwars  # or severance, hitchhiker, montypython, colossus
```

**Current Profile Annotations:**
```kotlin
// StarWarsLoggingAgenticEventListener.kt
@Component
@Profile("starwars")
class StarWarsLoggingAgenticEventListener : LoggingAgenticEventListener(...)

// SeveranceLoggingAgenticEventListener.kt  
@Service  // Note: Uses @Service instead of @Component
@Profile("severance") 
class SeveranceLoggingAgenticEventListener : LoggingAgenticEventListener(...)

// And similarly for hitchhiker, montypython, colossus...
```

### **Target Configuration**
```yaml
# application.yml
embabel:
  agent:
    logging:
      personality: starwars  # or severance, hitchhiker, montypython, colossus
      verbosity: info
      enableRuntimeSwitching: false
```

### **Property Configuration Class**
```kotlin
@ConfigurationProperties("embabel.agent.logging")
data class PersonalityConfiguration(
    var personality: String = "default",
    var verbosity: String = "info",
    var enableRuntimeSwitching: Boolean = false
)
```

### **Profile-Specific Changes Required**

#### **1. StarWars Personality**
**File:** `src/main/kotlin/com/embabel/agent/event/logging/personality/starwars/StarWarsLoggingAgenticEventListener.kt`

```kotlin
// REMOVE:
import org.springframework.context.annotation.Profile
@Profile("starwars")

// ADD:
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
@ConditionalOnProperty(
    name = "embabel.agent.logging.personality", 
    havingValue = "starwars"
)
```

#### **2. Severance Personality**
**File:** `src/main/kotlin/com/embabel/agent/event/logging/personality/severance/SeveranceLoggingAgenticEventListener.kt`

```kotlin
// REMOVE:
import org.springframework.context.annotation.Profile
@Profile("severance")

// ADD:
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
@ConditionalOnProperty(
    name = "embabel.agent.logging.personality", 
    havingValue = "severance"
)
```

#### **3. Hitchhiker Personality**
**File:** `src/main/kotlin/com/embabel/agent/event/logging/personality/hitchhiker/HitchhikerLoggingAgenticEventListener.kt`

```kotlin
// REMOVE:
import org.springframework.context.annotation.Profile
@Profile("hitchhiker")

// ADD:
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
@ConditionalOnProperty(
    name = "embabel.agent.logging.personality", 
    havingValue = "hitchhiker"
)
```

#### **4. Monty Python Personality**
**File:** `src/main/kotlin/com/embabel/agent/event/logging/personality/montypython/MontyPythonLoggingAgenticEventListener.kt`

```kotlin
// REMOVE:
import org.springframework.context.annotation.Profile
@Profile("montypython")

// ADD:
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
@ConditionalOnProperty(
    name = "embabel.agent.logging.personality", 
    havingValue = "montypython"
)
```

#### **5. Colossus Personality**
**File:** `src/main/kotlin/com/embabel/agent/event/logging/personality/colossus/ColossusLoggingAgenticEventListener.kt`

```kotlin
// REMOVE:
import org.springframework.context.annotation.Profile
@Profile("colossus")

// ADD:
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
@ConditionalOnProperty(
    name = "embabel.agent.logging.personality", 
    havingValue = "colossus"
)
```

### **Environment Variables**
```bash
# Enable specific personality
export EMBABEL_AGENT_LOGGING_PERSONALITY=starwars
export EMBABEL_AGENT_LOGGING_VERBOSITY=debug
export EMBABEL_AGENT_LOGGING_ENABLERUNTIMESWITCHING=true

# System properties (alternative)
-Dembabel.agent.logging.personality=severance
-Dembabel.agent.logging.verbosity=info
```

### **Migration Benefits**
- **No profile dependencies** - Library-centric design
- **Property validation** - Clear error messages for invalid personalities  
- **Environment flexibility** - Easy override with env vars
- **IDE support** - Auto-completion for personality values
- **Plugin foundation** - Prepares for runtime switching capability

---

## 1. Shell Profile Migration

### **Current State**
```yaml
# application-shell.yml
spring:
  main:
    web-application-type: none
  shell:
    command:
      exit:
        enabled: false
      quit:
        enabled: false
    history:
      enabled: true
    interactive:
      enabled: true

logging:
  level:
    org.springframework.shell: WARN
```

### **Target Configuration**
```yaml
# application.yml
embabel:
  shell:  # Note: Uses embabel.shell.* (existing structure)
    enabled: true  # New property-based activation
    lineLength: 140
    chat:
      confirmGoals: true
      bindConversation: false
```

### **Existing Property Configuration Class**
**File:** `embabel-agent-shell/src/main/kotlin/com/embabel/agent/shell/config/ShellProperties.kt`
```kotlin
// Existing class - will be EXTENDED, not replaced
@ConfigurationProperties(prefix = "embabel.shell")  
data class ShellProperties(
    val lineLength: Int = 140,
    val chat: ChatConfig = ChatConfig(),
    // ADD: New property-based activation support
    val enabled: Boolean = false  // Add this for property-based activation
) {
    data class ChatConfig(
        val confirmGoals: Boolean = true,
        val bindConversation: Boolean = false,
    )
}
```

### **Module Context**
- **Existing Module**: `embabel-agent-shell` (separate from `embabel-agent-api`)
- **Existing Configuration**: `ShellConfiguration.kt` and `ShellProperties.kt` already exist
- **Migration Strategy**: Extend existing structure with dual support, don't recreate

### **Environment Variable Override**
```bash
export EMBABEL_SHELL_ENABLED=true  # Note: Uses EMBABEL_SHELL_* (existing namespace)
export EMBABEL_SHELL_LINELENGTH=100
export EMBABEL_SHELL_CHAT_CONFIRMGOALS=false
```

---

## 2. Neo4j Profile Migration

### **Current State**
```yaml
# application-neo.yml
spring:
  neo4j:
    authentication:
      username: ${NEO4J_USERNAME:neo4j}
      password: ${NEO4J_PASSWORD:brahmsian}  # ⚠️ Default password risk
    uri: bolt://localhost:7687
```

### **Target Configuration**
```yaml
# application.yml
embabel:
  agent:
    infrastructure:
      neo4j:
        enabled: true
        uri: ${NEO4J_URI:bolt://localhost:7687}
        authentication:
          username: ${NEO4J_USERNAME:}  # No default - must be provided
          password: ${NEO4J_PASSWORD:}  # No default - must be provided
```

### **Property Configuration Class**
```kotlin
@ConfigurationProperties("embabel.agent.infrastructure.neo4j")
@Validated
data class Neo4jConfiguration(
    var enabled: Boolean = false,
    
    @field:NotBlank(message = "Neo4j URI must be provided when enabled")
    var uri: String = "",
    
    @field:Valid
    var authentication: AuthConfig = AuthConfig()
) {
    data class AuthConfig(
        @field:NotBlank(message = "Neo4j username must be provided")
        var username: String = "",
        
        @field:NotBlank(message = "Neo4j password must be provided")
        var password: String = ""
    )
}
```

### **Environment Variables (Required)**
```bash
# Required for Neo4j integration
export NEO4J_URI=bolt://prod-cluster:7687
export NEO4J_USERNAME=produser
export NEO4J_PASSWORD=securepassword

# Enable Neo4j integration
export EMBABEL_AGENT_INFRASTRUCTURE_NEO4J_ENABLED=true
```

### **Auto-Configuration**
```kotlin
@Configuration
@ConditionalOnProperty("embabel.agent.infrastructure.neo4j.enabled", havingValue = "true")
class Neo4jAutoConfiguration(private val neo4jConfig: Neo4jConfiguration) {
    
    @Bean
    @ConfigurationProperties("spring.neo4j")
    fun neo4jProperties(): Neo4jProperties {
        val props = Neo4jProperties()
        props.uri = neo4jConfig.uri
        props.authentication.username = neo4jConfig.authentication.username
        props.authentication.password = neo4jConfig.authentication.password
        return props
    }
}
```

---

## 3. MCP Profiles Migration (docker-ce & docker-desktop)

### **Current State - Docker CE**
```yaml
# application-docker-ce.yml
spring:
  ai:
    mcp:
      client:
        enabled: true
        stdio:
          connections:
            brave-search-mcp:
              command: docker
              args: ["run", "-i", "--rm", "-e", "BRAVE_API_KEY", "mcp/brave-search"]
              env:
                BRAVE_API_KEY: ${BRAVE_API_KEY}
            github-mcp:
              command: docker
              args: ["run", "-i", "--rm", "-e", "GITHUB_PERSONAL_ACCESS_TOKEN", "mcp/github"]
              env:
                GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_PERSONAL_ACCESS_TOKEN}
            # ... more MCP servers
```

### **Target Configuration**
```yaml
# application.yml
embabel:
  agent:
    infrastructure:
      mcp:
        enabled: true
        client:
          name: embabel
          version: 1.0.0
          requestTimeout: 30s
          type: SYNC
        servers:
          brave-search:
            command: docker
            args: ["run", "-i", "--rm", "-e", "BRAVE_API_KEY", "mcp/brave-search"]
            env:
              BRAVE_API_KEY: ${BRAVE_API_KEY:}  # Must be provided
          github:
            command: docker
            args: ["run", "-i", "--rm", "-e", "GITHUB_PERSONAL_ACCESS_TOKEN", "mcp/github"]
            env:
              GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_PERSONAL_ACCESS_TOKEN:}  # Must be provided
          wikipedia:
            command: docker
            args: ["run", "-i", "--rm", "mcp/wikipedia-mcp"]
            # No credentials required
```

### **Property Configuration Class**
```kotlin
@ConfigurationProperties("embabel.agent.infrastructure.mcp")
data class McpConfiguration(
    var enabled: Boolean = false,
    var client: ClientConfig = ClientConfig(),
    var servers: Map<String, McpServerConfig> = emptyMap()
) {
    data class ClientConfig(
        var name: String = "embabel",
        var version: String = "1.0.0",
        var requestTimeout: Duration = Duration.ofSeconds(30),
        var type: String = "SYNC"
    )
    
    data class McpServerConfig(
        var command: String = "",
        var args: List<String> = emptyList(),
        var env: Map<String, String> = emptyMap()  // No default credentials
    )
}
```

### **Environment Variables (Required for some servers)**
```bash
# MCP Server API Keys (required for specific servers)
export BRAVE_API_KEY=your_brave_search_api_key
export GITHUB_PERSONAL_ACCESS_TOKEN=ghp_your_github_token
export GOOGLE_MAPS_API_KEY=your_google_maps_key

# Enable MCP integration
export EMBABEL_AGENT_INFRASTRUCTURE_MCP_ENABLED=true
```

### **Docker Desktop Simplified Configuration**
```yaml
# application.yml (Docker Desktop preset)
embabel:
  agent:
    infrastructure:
      mcp:
        enabled: true
        preset: docker-desktop  # Simplified configuration
        # Equivalent to:
        # servers:
        #   docker-desktop:
        #     command: docker
        #     args: ["mcp", "gateway", "run"]
```

---

## 4. Observability Profile Migration

### **Current State**
```yaml
# application-observability.yml
spring:
  ai:
    chat:
      observations:
        enabled: true
        log-prompt: true
        log-completion: true
        include-completion: true
        include-prompt: true
        include-error-logging: true
    client:
      observations:
        log-prompt: true
        log-completion: true
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    env:
      show-values: always
      show-details: always
    health:
      show-details: always
  observations:
    enabled: true
    web:
      response:
        enabled: true
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

### **Target Configuration**
```yaml
# application.yml
embabel:
  agent:
    infrastructure:
      observability:
        enabled: true
        ai:
          chat:
            observations:
              enabled: true
              logPrompt: true
              logCompletion: true
              includeCompletion: true
              includePrompt: true
              includeErrorLogging: true
          client:
            observations:
              logPrompt: true
              logCompletion: true
        tracing:
          enabled: true
          samplingProbability: 1.0
          zipkinEndpoint: ${ZIPKIN_ENDPOINT:http://localhost:9411/api/v2/spans}
        management:
          endpoints:
            webExposureInclude: "*"
          endpoint:
            env:
              showValues: always
              showDetails: always
            health:
              showDetails: always
          observations:
            enabled: true
            webResponseEnabled: true
```

### **Property Configuration Class**
```kotlin
@ConfigurationProperties("embabel.agent.infrastructure.observability")
data class ObservabilityConfiguration(
    var enabled: Boolean = false,
    var ai: AiObservabilityConfig = AiObservabilityConfig(),
    var tracing: TracingConfig = TracingConfig(),
    var management: ManagementConfig = ManagementConfig()
) {
    data class AiObservabilityConfig(
        var chat: ChatObservabilityConfig = ChatObservabilityConfig(),
        var client: ClientObservabilityConfig = ClientObservabilityConfig()
    )
    
    data class ChatObservabilityConfig(
        var observations: ObservationsConfig = ObservationsConfig()
    )
    
    data class ObservationsConfig(
        var enabled: Boolean = true,
        var logPrompt: Boolean = true,
        var logCompletion: Boolean = true,
        var includeCompletion: Boolean = true,
        var includePrompt: Boolean = true,
        var includeErrorLogging: Boolean = true
    )
    
    data class TracingConfig(
        var enabled: Boolean = true,
        var samplingProbability: Double = 1.0,
        var zipkinEndpoint: String = ""  // Must be provided
    )
    
    data class ManagementConfig(
        var endpoints: EndpointsConfig = EndpointsConfig(),
        var endpoint: EndpointConfig = EndpointConfig(),
        var observations: ManagementObservationsConfig = ManagementObservationsConfig()
    )
}
```

### **Environment Variables**
```bash
# Observability endpoints
export ZIPKIN_ENDPOINT=http://prod-zipkin:9411/api/v2/spans

# Enable observability
export EMBABEL_AGENT_INFRASTRUCTURE_OBSERVABILITY_ENABLED=true

# Optional: Adjust tracing sampling for production
export EMBABEL_AGENT_INFRASTRUCTURE_OBSERVABILITY_TRACING_SAMPLINGPROBABILITY=0.1
```

---

## Backward Compatibility Strategy

### **Phase 1: Dual Support (No Breaking Changes)**

All configurations support both old profile-based and new property-based activation:

```kotlin
@Configuration
class BackwardCompatibilityConfiguration {
    
    // NEW: Property-based (highest priority)
    @Bean
    @Primary
    @ConditionalOnProperty("embabel.agent.infrastructure.neo4j.enabled", havingValue = "true")
    fun neo4jFromProperties(neo4jConfig: Neo4jConfiguration): Neo4jConfig {
        return Neo4jConfig(neo4jConfig)
    }
    
    // OLD: Profile-based (fallback)
    @Bean
    @Profile("neo")
    @ConditionalOnMissingBean(Neo4jConfig::class)
    fun neo4jFromProfile(): Neo4jConfig {
        logger.warn("DEPRECATED: 'neo' profile detected. Migrate to embabel.agent.infrastructure.neo4j.enabled=true")
        return Neo4jConfig(Neo4jConfiguration(enabled = true))
    }
}
```

### **Phase 2: Deprecation Warnings**

```kotlin
@Component
class ProfileDeprecationWarner(private val environment: Environment) {
    
    @PostConstruct
    fun warnAboutDeprecatedProfiles() {
        val deprecatedProfiles = mapOf(
            "shell" to "embabel.agent.shell.enabled=true",
            "neo" to "embabel.agent.infrastructure.neo4j.enabled=true",
            "docker-ce" to "embabel.agent.infrastructure.mcp.enabled=true",
            "docker-desktop" to "embabel.agent.infrastructure.mcp.enabled=true",
            "observability" to "embabel.agent.infrastructure.observability.enabled=true"
        )
        
        val activeDeprecated = environment.activeProfiles.intersect(deprecatedProfiles.keys)
        
        activeDeprecated.forEach { profile ->
            val replacement = deprecatedProfiles[profile]
            logger.warn("""
                DEPRECATED: Profile '$profile' is deprecated and will be removed in a future version.
                Migrate to property-based configuration: $replacement
                See PROFILES_MIGRATION_GUIDE.md for detailed migration instructions.
            """.trimIndent())
        }
    }
}
```

### **Phase 3: Profile Removal & Template Provision**

- Remove `application-{profile}.yml` files
- Remove profile-based conditional beans
- Property-only configuration
- **Add application configuration templates** for developer guidance

#### **Application Templates Structure**
```
src/main/resources/application-templates/
├── application-development.yml      # Development environment example
├── application-production.yml       # Production environment example  
├── application-minimal.yml          # Minimal configuration example
├── application-full-featured.yml    # Complete configuration with all options
├── application-personality-demo.yml # Personality plugin examples
└── README.md                       # Template usage instructions
```

#### **Template Examples**

**Development Template (`application-development.yml`):**
```yaml
# Copy to your src/main/resources/application.yml and customize
embabel:
  agent:
    platform:
      test:
        mockMode: true
  agent:
    logging:
      personality: starwars
      verbosity: debug
    infrastructure:
      neo4j:
        enabled: true
        uri: bolt://localhost:7687
        authentication:
          username: ${NEO4J_USERNAME:}
          password: ${NEO4J_PASSWORD:}
  shell:
    enabled: true
    chat:
      confirmGoals: true
```

**Production Template (`application-production.yml`):**
```yaml
# Copy to your src/main/resources/application.yml and customize
embabel:
  agent:
    platform:
      test:
        mockMode: false
  agent:
    logging:
      personality: corporate
      verbosity: info
    infrastructure:
      observability:
        enabled: true
        tracing:
          zipkinEndpoint: ${ZIPKIN_ENDPOINT:}
      neo4j:
        enabled: true
        uri: ${NEO4J_URI:}
        authentication:
          username: ${NEO4J_USERNAME:}
          password: ${NEO4J_PASSWORD:}
  shell:
    enabled: false  # No interactive shell in production
```

**Template Benefits:**
- **Migration support** - Show equivalent property configs for old profiles
- **Environment examples** - Different deployment scenarios  
- **Developer onboarding** - Clear examples of property-based configuration
- **Living documentation** - Templates stay current with code changes

---

## Environment Variables Best Practices

### **Naming Convention**
```bash
# Pattern: EMBABEL_AGENT_SECTION_SUBSECTION_PROPERTY
export EMBABEL_AGENT_INFRASTRUCTURE_NEO4J_URI=bolt://prod:7687
export EMBABEL_AGENT_INFRASTRUCTURE_MCP_ENABLED=true
export EMBABEL_AGENT_SHELL_ENABLED=true
```

### **Security Guidelines**

1. **Never commit credentials** to version control
2. **Use secrets management** in production (Kubernetes secrets, AWS Secrets Manager, etc.)
3. **Validate required properties** at startup
4. **Use different credentials** for different environments

```bash
# Development
export NEO4J_USERNAME=dev_user
export NEO4J_PASSWORD=dev_password

# Production (from secrets manager)
export NEO4J_USERNAME=$(aws secretsmanager get-secret-value --secret-id prod/neo4j/username --query SecretString --output text)
export NEO4J_PASSWORD=$(aws secretsmanager get-secret-value --secret-id prod/neo4j/password --query SecretString --output text)
```

---

## Configuration Examples by Environment

### **Development Environment**
```yaml
# application.yml (development)
embabel:
  agent:
    shell:
      enabled: true
    infrastructure:
      neo4j:
        enabled: true
        uri: bolt://localhost:7687
        authentication:
          username: ${NEO4J_USERNAME:}
          password: ${NEO4J_PASSWORD:}
      mcp:
        enabled: false  # Disabled in development
      observability:
        enabled: true
        tracing:
          zipkinEndpoint: http://localhost:9411/api/v2/spans
          samplingProbability: 1.0  # Full sampling in dev
```

### **Production Environment**
```yaml
# application.yml (production)
embabel:
  agent:
    shell:
      enabled: false  # No interactive shell in production
    infrastructure:
      neo4j:
        enabled: true
        uri: ${NEO4J_URI:}
        authentication:
          username: ${NEO4J_USERNAME:}
          password: ${NEO4J_PASSWORD:}
      mcp:
        enabled: true
        servers:
          github:
            command: docker
            args: ["run", "-i", "--rm", "-e", "GITHUB_PERSONAL_ACCESS_TOKEN", "mcp/github"]
            env:
              GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_PERSONAL_ACCESS_TOKEN:}
      observability:
        enabled: true
        tracing:
          zipkinEndpoint: ${ZIPKIN_ENDPOINT:}
          samplingProbability: 0.1  # Reduced sampling in prod
```

---

## Troubleshooting

### **Common Issues**

#### **1. Missing Required Properties**
```
Error: Neo4j username must be provided when enabled
```
**Solution:**
```bash
export NEO4J_USERNAME=your_username
export NEO4J_PASSWORD=your_password
```

#### **2. Profile Still Active**
```
WARN: DEPRECATED: Profile 'neo' is deprecated
```
**Solution:** Remove profile activation and use properties:
```yaml
# Remove:
spring:
  profiles:
    active: neo

# Add:
embabel:
  agent:
    infrastructure:
      neo4j:
        enabled: true
```

#### **3. MCP Servers Not Connecting**
```
Error: MCP server 'github' failed to connect
```
**Solution:** Check required API keys:
```bash
export GITHUB_PERSONAL_ACCESS_TOKEN=ghp_your_token
```

#### **4. Observability Not Working**
**Solution:** Check Zipkin endpoint and ensure it's accessible:
```bash
export ZIPKIN_ENDPOINT=http://your-zipkin-server:9411/api/v2/spans
```

### **Validation Commands**

```bash
# Verify environment variables are set
echo $EMBABEL_AGENT_INFRASTRUCTURE_NEO4J_ENABLED
echo $NEO4J_USERNAME  # Should show username
echo $NEO4J_PASSWORD  # Should show password

# Test configuration loading
java -jar app.jar --spring.config.location=classpath:application.yml
```

---

## Migration Timeline

### **Immediate (Phase 1)**
- ✅ Add property-based configuration classes
- ✅ Maintain backward compatibility with profiles
- ✅ Update documentation

### **Next Release (Phase 2)**
- ⏳ Add deprecation warnings for profile usage
- ⏳ Provide migration tooling/scripts
- ⏳ Update examples to use property-based configuration

### **Future Release (Phase 3)**
- 🔄 Remove profile-based configuration entirely
- 🔄 Delete `application-{profile}.yml` files
- 🔄 Property-only configuration

---

## Benefits of Property-Based Configuration

### **Enhanced Security**
- No hardcoded credentials
- Clear separation of sensitive data
- Environment-specific credential management

### **Better Developer Experience**
- IDE auto-completion and validation
- Clear property hierarchy
- Self-documenting configuration structure

### **Improved Deployment Flexibility**
- Environment variable override support
- Kubernetes ConfigMap integration
- Docker-friendly configuration

### **Library-Centric Design**
- No Spring profile dependencies
- Suitable for embedded usage
- Framework vs application concerns clearly separated

---

This migration guide ensures a secure, systematic transition from profile-based to property-based configuration while maintaining backward compatibility and improving overall system security and developer experience.