# Personality Plugin Infrastructure - Detailed Iterative Implementation Plan

## Overview

Transform the personality system from profile-based to property-based activation with plugin architecture support. This plan focuses specifically on the personality system as a **test case** for the broader library-centric transformation.

## Implementation Strategy

**Primary Goal**: Property-based configuration foundation with dual support during transition  
**Approach**: Incremental commits with **full backward compatibility** (no breaking changes)  
**Timeline**: 14 iterations, each representing 1 commit  
**Integration**: This plan integrates with broader profile migration (see [PROFILES_MIGRATION_GUIDE.md](PROFILES_MIGRATION_GUIDE.md))

### **Dual Support Strategy**
Each iteration implements **both old and new systems simultaneously**:
- **Property-based activation** (primary, `@Primary`)
- **Profile-based activation** (fallback, `@ConditionalOnMissingBean`) 
- **Deprecation warnings** for profile usage
- **No file deletion** until final cleanup iterations

### **Package Structure Strategy**

**Existing Module Structure Analysis:**
- **Shell Module**: Already has `ShellConfiguration.kt` and `ShellProperties.kt` in `embabel-agent-shell` module
- **Core Module**: Configuration classes will be added to `embabel-agent-api` module
- **Module Independence**: Shell configuration remains in shell module (separate deployable unit)

**Proposed Configuration Class Organization:**

#### **Core Framework (`embabel-agent-api`)**
```
com.embabel.agent.config/
├── framework/                          # Framework internal configs
│   ├── FrameworkPropertiesConfig.kt    # embabel.framework.*
│   ├── ScanningConfiguration.kt        # embabel.framework.scanning.*
│   └── RankingConfiguration.kt         # embabel.framework.ranking.*
│
├── agent/                              # Application-level configs  
│   ├── logging/
│   │   └── PersonalityConfiguration.kt # embabel.agent.logging.*
│   ├── infrastructure/
│   │   ├── Neo4jConfiguration.kt       # embabel.agent.infrastructure.neo4j.*
│   │   ├── McpConfiguration.kt         # embabel.agent.infrastructure.mcp.*
│   │   └── ObservabilityConfiguration.kt # embabel.agent.infrastructure.observability.*
│   └── models/
│       └── ModelConfiguration.kt      # embabel.agent.models.*
```

#### **Shell Module (`embabel-agent-shell`)** - Keep Existing
```
com.embabel.agent.shell.config/
├── ShellConfiguration.kt               # Existing - keep as-is
└── ShellProperties.kt                  # Existing - embabel.shell.* 
```

#### **AutoConfiguration Strategy (TBD)**
**Options:**
1. **Single module**: `embabel-agent-autoconfigure` with all auto-configuration classes
2. **Multi-module**: `embabel-agent-autoconfigure-shell`, `embabel-agent-autoconfigure-neo4j`, etc.

**Rationale:**
- **Clear separation** between framework vs agent configuration packages
- **Module independence** maintained (shell stays in shell module)
- **Logical grouping** by functional area (logging, infrastructure, models)
- **Matches property hierarchy** in package naming

## Complete Implementation Sequence

### **Phase A: Foundation & Core Migrations (Iterations 0-1)**

**Iteration 0: Framework Property Foundation (Dual Support)**
- **Goal**: Establish proper property segregation between framework internals and application configuration
- **Files to create**: `FrameworkPropertiesConfig.kt`
- **Files to modify**: `AgentScanningProperties.kt`, `LlmRanker.kt`
- **Migration**: `embabel.agent-platform.*` → `embabel.framework.*`
- **Details**: See detailed section below

**Iteration 1: Shell Profile Migration (Dual Support)**
- **Goal**: Add property-based activation while maintaining `application-shell.yml` and `@Profile("shell")` compatibility
- **Files to modify**: `embabel-agent-shell/src/main/kotlin/com/embabel/agent/shell/config/ShellProperties.kt` (add dual support)
- **Files to create**: `ShellAutoConfiguration.kt` (with dual support logic for both property and profile activation)
- **Files to keep**: `application-shell.yml` (maintain for backward compatibility - **will be deprecated later**)
- **Profiles to keep**: `@Profile("shell")` annotations (maintain for backward compatibility - **will be deprecated later**)
- **Note**: Shell module already has configuration classes - extend existing structure
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Section 1](PROFILES_MIGRATION_GUIDE.md#1-shell-profile-migration)

### **Phase B: Personality Plugin Infrastructure (Iterations 2-7)**

**Iteration 2: Personality Property-Based Configuration (Dual Support)**
- **Goal**: Add `embabel.agent.logging.*` while maintaining profile activation compatibility
- **Files to create**: `PersonalityConfiguration.kt`
- **Files to modify**: All 5 personality classes (keep both `@Profile` and `@ConditionalOnProperty`)
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Section 0: Personality Profiles](PROFILES_MIGRATION_GUIDE.md#0-personality-profiles-migration)
- **Details**: See detailed section below

**Iteration 3: Property Integration & Validation**
- **Goal**: Robust property system with validation and fallback mechanisms
- **Details**: See detailed section below

**Iteration 4: Core Plugin Infrastructure**
- **Goal**: Registry and provider interfaces for dynamic personality management
- **Details**: See detailed section below

**Iteration 5: Provider Implementation Wrappers**
- **Goal**: Convert existing personalities to plugin providers
- **Details**: See detailed section below

**Iteration 6: Runtime Management & API**
- **Goal**: Dynamic personality switching capabilities
- **Details**: See detailed section below

**Iteration 7: Enhanced Dynamic Properties**
- **Goal**: Advanced property dynamism and external configuration support
- **Details**: See detailed section below

### **Phase C: Remaining Profile Migrations (Iterations 8-11)**

**Iteration 8: Neo4j Profile Migration (Dual Support)**
- **Goal**: Add `embabel.agent.infrastructure.neo4j.*` while maintaining `application-neo.yml` compatibility
- **Files to create**: `Neo4jConfiguration.kt`, `Neo4jAutoConfiguration.kt` (with dual support)
- **Files to keep**: `application-neo.yml` (maintain for backward compatibility)
- **Security**: Remove hardcoded credentials, require environment variables
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Section 2](PROFILES_MIGRATION_GUIDE.md#2-neo4j-profile-migration)

**Iteration 9: MCP Profiles Migration (Dual Support)**
- **Goal**: Add `embabel.agent.infrastructure.mcp.*` while maintaining existing profile files
- **Files to create**: `McpConfiguration.kt`, `McpAutoConfiguration.kt` (with dual support)
- **Files to keep**: `application-docker-ce.yml`, `application-docker-desktop.yml`
- **Security**: Externalize API keys (GITHUB_TOKEN, BRAVE_API_KEY, etc.)
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Section 3](PROFILES_MIGRATION_GUIDE.md#3-mcp-profiles-migration-docker-ce--docker-desktop)

**Iteration 10: Observability Profile Migration (Dual Support)**
- **Goal**: Add `embabel.agent.infrastructure.observability.*` while maintaining `application-observability.yml`
- **Files to create**: `ObservabilityConfiguration.kt`, `ObservabilityAutoConfiguration.kt` (with dual support)
- **Files to keep**: `application-observability.yml` (maintain for backward compatibility)
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Section 4](PROFILES_MIGRATION_GUIDE.md#4-observability-profile-migration)

**Iteration 11: Add Deprecation Warnings**
- **Goal**: Warn users about profile usage, guide to property-based config
- **Files to create**: `ProfileDeprecationWarner.kt`
- **Profiles to deprecate**: `@Profile("shell")`, `@Profile("neo")`, `@Profile("docker-ce")`, `@Profile("docker-desktop")`, `@Profile("observability")`, personality profiles
- **Files to deprecate**: `application-shell.yml`, `application-neo.yml`, `application-docker-ce.yml`, `application-docker-desktop.yml`, `application-observability.yml`
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Backward Compatibility](PROFILES_MIGRATION_GUIDE.md#backward-compatibility-strategy)

### **Phase D: Profile Deprecation & Cleanup (Iterations 12-13)**

**Iteration 12: Remove Profile Support**
- **Goal**: Remove all `@Profile` annotations and profile-based logic
- **Files to modify**: All configuration classes, remove dual support
- **Profiles to remove**: `@Profile("shell")`, `@Profile("neo")`, `@Profile("docker-ce")`, `@Profile("docker-desktop")`, `@Profile("observability")`, personality profiles
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Phase 3](PROFILES_MIGRATION_GUIDE.md#phase-3-profile-removal)

**Iteration 13: Delete Profile Files & Add Templates**
- **Goal**: Remove all `application-{profile}.yml` files and provide property-based templates
- **Files to delete**: `application-shell.yml`, `application-neo.yml`, `application-docker-ce.yml`, `application-docker-desktop.yml`, `application-observability.yml`
- **Files to create**: Application configuration templates (see below)
- **Note**: Shell module may require special handling due to module independence
- **Documentation**: Update all examples to use property-based configuration

### **Application Templates Structure**
```
src/main/resources/application-templates/
├── application-development.yml      # Development environment example
├── application-production.yml       # Production environment example  
├── application-minimal.yml          # Minimal configuration example
├── application-full-featured.yml    # Complete configuration with all options
├── application-personality-demo.yml # Personality plugin examples
└── README.md                       # Template usage instructions
```

**Template Benefits:**
- **Developer onboarding** - Clear examples of property-based configuration
- **Migration support** - Show equivalent property configs for old profiles  
- **Environment examples** - Different deployment scenarios
- **Living documentation** - Templates stay current with code changes

**Total Timeline**: 14 iterations (1 framework foundation + 1 shell + 6 personality + 4 remaining profiles + 2 cleanup)  

---

## Future Iterations (Planning Placeholder)

### **Phase D: Model Provider Plugin Infrastructure (Future)**

**Purpose**: Apply lessons learned from personality plugin infrastructure to model providers

**Planned Iterations (TBD):**
- **Future Iteration A**: Model Provider Property-Based Configuration
  - Replace hardcoded model provider selection with `embabel.agent.models.provider=*`
  - Support: `openai`, `bedrock`, `ollama`, `anthropic`, etc.

- **Future Iteration B**: Model Provider Plugin Interface
  - Create `ModelProviderPlugin` interface
  - Implement `ModelProviderRegistry` with auto-discovery
  - Support runtime model provider switching

- **Future Iteration C**: Dynamic Model Configuration
  - Hot-reload model configurations
  - External model definition files
  - API endpoints for model management

- **Future Iteration D**: Advanced Model Features
  - Model capability detection
  - Cost-aware model selection
  - Performance-based model routing

**Dependencies**: 
- ✅ Framework Property Foundation (Iteration 0)
- ✅ Personality Plugin Infrastructure (Iterations 1-6) - **serves as reference implementation**
- ✅ Profile Migration Complete (Iterations 7-13)

**Notes**: 
- Model provider plugins will follow the same pattern established by personality plugins
- Property-based activation: `embabel.agent.models.provider=bedrock`
- Plugin discovery and runtime switching capabilities
- Enhanced configurability for model selection criteria

---

## Iteration 0: Framework Property Foundation

**Focus**: Establish proper property segregation between framework internals and application configuration

### Files to Create:
- `src/main/kotlin/com/embabel/agent/config/FrameworkPropertiesConfig.kt`

### Files to Modify:
- `src/main/kotlin/com/embabel/agent/core/deployment/AgentScanningProperties.kt`
- `src/main/kotlin/com/embabel/agent/spi/support/LlmRanker.kt`

### Changes:

**1. Create FrameworkPropertiesConfig.kt:**
```kotlin
@ConfigurationProperties("embabel.framework")
data class FrameworkPropertiesConfig(
    var scanning: ScanningConfig = ScanningConfig(),
    var ranking: RankingConfig = RankingConfig(),
    var llmOperations: LlmOperationsConfig = LlmOperationsConfig(),
    var processIdGeneration: ProcessIdGenerationConfig = ProcessIdGenerationConfig(),
    var test: TestConfig = TestConfig()
) {
    data class ScanningConfig(
        var annotation: Boolean = true,
        var bean: Boolean = false
    )
    
    data class RankingConfig(
        var llm: String? = null,
        var maxAttempts: Int = 5,
        var backoffMillis: Long = 1000,
        var backoffMultiplier: Double = 2.0,
        var backoffMaxInterval: Long = 30000
    )
    
    data class LlmOperationsConfig(
        var timeout: String = "30s",
        var retryStrategy: String = "exponential"
    )
    
    data class ProcessIdGenerationConfig(
        var strategy: String = "uuid"
    )
    
    data class TestConfig(
        var mockMode: Boolean = true
    )
}
```

**2. Update existing properties to use framework namespace:**
- `embabel.agent-platform.scanning.*` → `embabel.framework.scanning.*`
- `embabel.agent-platform.ranking.*` → `embabel.framework.ranking.*`

### Testing:
- Verify framework properties load correctly: `embabel.framework.scanning.annotation=true`
- Test environment variable override: `EMBABEL_FRAMEWORK_SCANNING_ANNOTATION=false`
- Ensure backward compatibility with existing `embabel.agent-platform.*` properties
- Test system property override: `-Dembabel.framework.test.mockMode=false`

---

## Iteration 1: Property-Based Configuration Foundation

**Focus**: Replace Spring profile dependencies with property-based activation for personalities

### Files to Modify:
- `src/main/kotlin/com/embabel/agent/event/logging/personality/starwars/StarWarsLoggingAgenticEventListener.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/severance/SeveranceLoggingAgenticEventListener.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/hitchhiker/HitchhikerLoggingAgenticEventListener.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/montypython/MontyPythonLoggingAgenticEventListener.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/colossus/ColossusLoggingAgenticEventListener.kt`

### Files to Create:
- `src/main/kotlin/com/embabel/agent/config/PersonalityConfiguration.kt`

### Changes:

**1. Create PersonalityConfiguration.kt:**
```kotlin
@ConfigurationProperties("embabel.agent.logging")
data class PersonalityConfiguration(
    var personality: String = "default",
    var verbosity: String = "info",
    var enableRuntimeSwitching: Boolean = false
)
```

**2. Update all 5 personality classes:**
- Replace `@Profile("personality-name")` with `@ConditionalOnProperty` activation
- **Detailed Changes**: See [PROFILES_MIGRATION_GUIDE.md - Section 0: Personality Profiles Migration](PROFILES_MIGRATION_GUIDE.md#0-personality-profiles-migration)
- **Files**: StarWars, Severance, Hitchhiker, MontyPython, Colossus event listeners

### Testing:
- Verify property-based activation works: `embabel.agent.logging.personality=starwars`
- Test environment variable override: `EMBABEL_AGENT_LOGGING_PERSONALITY=starwars`
- Test system property override: `-Dembabel.agent.logging.personality=severance`
- Ensure no profile dependencies remain

---

## Iteration 2: Property Integration & Validation

**Focus**: Robust property system with validation and fallback mechanisms

### Files to Modify:
- `src/main/kotlin/com/embabel/agent/config/PersonalityConfiguration.kt`
- `src/main/kotlin/com/embabel/agent/config/AgentPlatformConfiguration.kt`

### Files to Create:
- `src/main/kotlin/com/embabel/agent/config/validation/PersonalityConfigurationValidator.kt`

### Changes:

**1. Enhanced PersonalityConfiguration with validation:**
```kotlin
@ConfigurationProperties("embabel.agent.logging")
@Validated
data class PersonalityConfiguration(
    @field:Pattern(
        regexp = "^(default|starwars|severance|hitchhiker|montypython|colossus)$",
        message = "Personality must be one of: default, starwars, severance, hitchhiker, montypython, colossus"
    )
    var personality: String = "default",
    
    @field:Pattern(
        regexp = "^(debug|info|warn|error)$",
        message = "Verbosity must be one of: debug, info, warn, error"
    )
    var verbosity: String = "info",
    
    var enableRuntimeSwitching: Boolean = false
)
```

**2. Create fallback mechanism for invalid personalities:**
```kotlin
@Component
class PersonalityConfigurationValidator(
    private val personalityConfig: PersonalityConfiguration
) {
    
    @PostConstruct
    fun validateAndFallback() {
        val validPersonalities = setOf("default", "starwars", "severance", "hitchhiker", "montypython", "colossus")
        
        if (personalityConfig.personality !in validPersonalities) {
            logger.warn("Invalid personality '${personalityConfig.personality}'. Falling back to 'default'")
            personalityConfig.personality = "default"
        }
    }
}
```

### Testing:
- Test invalid personality names fall back to default
- Test property validation error messages
- Test environment variable precedence
- Verify backward compatibility with existing configurations

---

## Iteration 3: Core Plugin Infrastructure

**Focus**: Registry and provider interfaces for dynamic personality management

### Files to Create:
- `src/main/kotlin/com/embabel/agent/event/logging/PersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/PersonalityProviderRegistry.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/BasePersonalityProvider.kt`

### Changes:

**1. PersonalityProvider interface:**
```kotlin
interface PersonalityProvider {
    val name: String
    val description: String
    val version: String get() = "1.0.0"
    val author: String get() = "Embabel"
    fun createEventListener(): LoggingAgenticEventListener
    fun isAvailable(): Boolean = true
}
```

**2. PersonalityProviderRegistry with constructor DI:**
```kotlin
@Component
class PersonalityProviderRegistry(
    private val applicationContext: ApplicationContext,
    private val personalityConfig: PersonalityConfiguration
) {
    private val providers = mutableMapOf<String, PersonalityProvider>()
    private var activePersonality: LoggingAgenticEventListener? = null
    
    @PostConstruct
    fun initialize() {
        discoverProviders()
        activatePersonality(personalityConfig.personality)
    }
    
    private fun discoverProviders() {
        val discoveredProviders = applicationContext.getBeansOfType(PersonalityProvider::class.java)
        discoveredProviders.values.forEach { registerProvider(it) }
    }
    
    fun switchPersonality(name: String): Boolean
    fun getActivePersonality(): LoggingAgenticEventListener
    fun getAvailablePersonalities(): Set<String>
}
```

**3. BasePersonalityProvider abstract class:**
```kotlin
@Component
abstract class BasePersonalityProvider : PersonalityProvider {
    protected abstract fun createSpecificEventListener(): LoggingAgenticEventListener
    
    final override fun createEventListener(): LoggingAgenticEventListener {
        return if (isAvailable()) {
            createSpecificEventListener()
        } else {
            throw IllegalStateException("Personality $name is not available")
        }
    }
}
```

### Testing:
- Verify provider auto-discovery works
- Test registry initialization
- Ensure integration with existing property-based activation

---

## Iteration 4: Provider Implementation Wrappers

**Focus**: Convert existing personalities to plugin providers

### Files to Create:
- `src/main/kotlin/com/embabel/agent/event/logging/personality/starwars/StarWarsPersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/severance/SeverancePersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/hitchhiker/HitchhikerPersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/montypython/MontyPythonPersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/colossus/ColossusPersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/DefaultPersonalityProvider.kt`

### Changes:

**1. StarWars Provider (example pattern):**
```kotlin
@Component
class StarWarsPersonalityProvider : BasePersonalityProvider() {
    override val name = "starwars"
    override val description = "Star Wars themed logging personality with ASCII art and themed messages"
    override val author = "Embabel Core Team"
    
    override fun createSpecificEventListener(): LoggingAgenticEventListener {
        return StarWarsLoggingAgenticEventListener()
    }
}
```

**2. Apply same pattern to all personalities:**
- SeverancePersonalityProvider
- HitchhikerPersonalityProvider  
- MontyPythonPersonalityProvider
- ColossusPersonalityProvider
- DefaultPersonalityProvider

**3. Update registry to work with providers while maintaining property activation**

### Testing:
- Verify all personalities are discovered as providers
- Test provider metadata (name, description, version)
- Ensure existing personality functionality unchanged
- Test provider-based creation vs direct instantiation

---

## Iteration 5: Runtime Management & API

**Focus**: Dynamic personality switching capabilities

### Files to Create:
- `src/main/kotlin/com/embabel/agent/web/rest/PersonalityManagementController.kt`
- `src/main/kotlin/com/embabel/agent/config/PersonalityManagementConfiguration.kt`

### Files to Modify:
- `src/main/kotlin/com/embabel/agent/event/logging/PersonalityProviderRegistry.kt`

### Changes:

**1. Enhanced registry with runtime switching:**
```kotlin
// Add to PersonalityProviderRegistry
fun switchPersonality(personalityName: String): Boolean {
    return try {
        val newPersonality = activatePersonality(personalityName)
        personalityConfig.personality = personalityName
        
        // Notify existing components of personality change
        applicationContext.publishEvent(PersonalityChangedEvent(personalityName, newPersonality))
        
        logger.info("Switched to personality: $personalityName")
        true
    } catch (e: Exception) {
        logger.error("Failed to switch to personality: $personalityName", e)
        false
    }
}
```

**2. Management Controller:**
```kotlin
@RestController
@RequestMapping("/api/personality")
@ConditionalOnProperty("embabel.framework.management.personalityUpdatesEnabled", havingValue = "true")
class PersonalityManagementController(
    private val personalityRegistry: PersonalityProviderRegistry
) {
    
    @GetMapping("/current")
    fun getCurrentPersonality(): PersonalityInfo
    
    @GetMapping("/available")
    fun getAvailablePersonalities(): Map<String, PersonalityMetadata>
    
    @PostMapping("/switch/{name}")
    fun switchPersonality(@PathVariable name: String): ResponseEntity<ApiResponse>
    
    @PostMapping("/reload")
    fun reloadPersonalities(): ResponseEntity<ApiResponse>
}
```

### Testing:
- Test runtime personality switching via API
- Verify personality change events are published
- Test hot-reload functionality
- Ensure management endpoints are conditionally enabled

---

## Iteration 6: Enhanced Dynamic Properties

**Focus**: Advanced property dynamism and external configuration support

### Files to Create:
- `src/main/kotlin/com/embabel/agent/config/ExternalConfigurationLoader.kt`
- `src/main/kotlin/com/embabel/agent/config/PersonalityConfigurationRefresher.kt`

### Files to Modify:
- `src/main/kotlin/com/embabel/agent/config/PersonalityConfiguration.kt`
- `src/main/resources/META-INF/spring-configuration-metadata.json`

### Changes:

**1. External configuration directory support:**
```kotlin
@Component
class ExternalConfigurationLoader {
    
    @PostConstruct
    fun loadExternalConfiguration() {
        val externalConfigDirs = listOf(
            Paths.get(System.getProperty("user.home"), ".embabel"),
            Paths.get("/etc/embabel"),
            Paths.get("./config")
        )
        
        externalConfigDirs.forEach { dir ->
            loadPersonalityConfigFromDirectory(dir)
        }
    }
}
```

**2. Configuration metadata for IDE support:**
```json
{
  "properties": [
    {
      "name": "embabel.agent.logging.personality",
      "type": "java.lang.String",
      "description": "The personality theme for agent logging output",
      "defaultValue": "default",
      "hints": {
        "values": [
          {"value": "default", "description": "Standard Embabel logging"},
          {"value": "starwars", "description": "Star Wars themed logging"},
          {"value": "severance", "description": "Lumon Industries themed logging"},
          {"value": "hitchhiker", "description": "Hitchhiker's Guide themed logging"},
          {"value": "montypython", "description": "Monty Python themed logging"},
          {"value": "colossus", "description": "1970s sci-fi themed logging"}
        ]
      }
    }
  ]
}
```

**3. Runtime property updates via actuator:**
```kotlin
@Component
@ConditionalOnProperty("embabel.framework.management.configRefreshEnabled", havingValue = "true")
class PersonalityConfigurationRefresher(
    private val personalityRegistry: PersonalityProviderRegistry
) {
    
    @EventListener
    fun handleConfigurationRefresh(event: EnvironmentChangeEvent) {
        if (event.keys.contains("embabel.agent.logging.personality")) {
            val newPersonality = environment.getProperty("embabel.agent.logging.personality", "default")
            personalityRegistry.switchPersonality(newPersonality)
        }
    }
}
```

### Testing:
- Test external configuration loading from `~/.embabel/`
- Verify IDE auto-completion works
- Test runtime configuration updates
- Test Kubernetes ConfigMap integration
- Verify enhanced property validation with meaningful error messages

---

## Success Criteria

### Functional Requirements:
- ✅ All personalities work with property-based activation
- ✅ Runtime personality switching without restart
- ✅ Auto-discovery of personality providers
- ✅ Backward compatibility with existing configurations
- ✅ Environment variable and system property overrides work
- ✅ Validation with meaningful error messages

### Technical Requirements:
- ✅ No Spring profile dependencies in personality system
- ✅ Clean plugin architecture with provider interface
- ✅ Constructor-based dependency injection throughout
- ✅ Comprehensive test coverage for all iterations
- ✅ IDE support with auto-completion and validation

### Documentation:
- ✅ Updated README with high-level strategic direction
- ✅ Complete iteration plan with implementation details
- ✅ Migration guide for developers
- ✅ API documentation for management endpoints

This iteration plan provides the detailed, accurate implementation steps for the personality plugin infrastructure transformation, serving as the authoritative guide for development work.