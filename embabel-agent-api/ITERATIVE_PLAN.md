# Personality Plugin Infrastructure - Detailed Iterative Implementation Plan

## Table of Contents

### **Main Sections**
- [Overview](#overview)
- [Implementation Strategy](#implementation-strategy)
- [Package Structure Strategy](#package-structure-strategy)
- [Complete Implementation Sequence](#complete-implementation-sequence)
  - [Phase A: Foundation & Core Migrations (Iterations 0-2)](#phase-a-foundation--core-migrations-iterations-0-2)
  - [Phase B: Personality Plugin Infrastructure (Iterations 3-8)](#phase-b-personality-plugin-infrastructure-iterations-3-8)
  - [Phase C: Remaining Profile Migrations (Iterations 9-12)](#phase-c-remaining-profile-migrations-iterations-9-12)
  - [Phase D: Profile Deprecation & Cleanup (Iterations 13-14)](#phase-d-profile-deprecation--cleanup-iterations-13-14)
- [Application Templates Structure](#application-templates-structure)
- [Future Iterations (Planning Placeholder)](#future-iterations-planning-placeholder)

### **Detailed Implementation**
- [Appendix A: Iteration 0 Implementation Details](#appendix-a-iteration-0---platform-property-foundation)

### **Migration Progress Tracking**
- [Appendix A: Iteration 0 - Platform Property Foundation](#appendix-a-iteration-0---platform-property-foundation) ✅ COMPLETED
- [Appendix B: Iteration 1 - Platform Property @ConfigurationProperties Migration](#appendix-b-iteration-1---platform-property-configurationproperties-migration) 🔄 NEXT

---

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
├── AgentPlatformProperties.kt          # embabel.agent.platform.*
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

### **Phase A: Foundation & Core Migrations (Iterations 0-2)**

**Iteration 0: Platform Property Foundation**
- **Goal**: Establish proper property segregation between platform internals and application configuration
- **Files**: 4 new + 9 modified + documentation updates
- **Migration**: Consolidate existing `embabel.agent-platform.*` properties under `embabel.agent.platform.*`
- **Details**: [Appendix A: Iteration 0 Implementation Details](#appendix-a-iteration-0---platform-property-foundation)

**Iteration 1: Platform Property @ConfigurationProperties Migration**
- **Goal**: Update model provider @ConfigurationProperties prefixes to use new platform namespace
- **Files to modify**: `AnthropicModels.kt`, `OpenAiModels.kt` (2 @ConfigurationProperties prefix updates)
- **Migration**: `embabel.anthropic.*` → `embabel.agent.platform.models.anthropic.*`, `embabel.openai.*` → `embabel.agent.platform.models.openai.*`
- **Detection**: Existing migration detection system will automatically warn users about deprecated usage
- **Details**: [Appendix B: Iteration 1 Implementation Details](#appendix-b-iteration-1---platform-property-configurationproperties-migration)

**Iteration 2: Shell Profile Migration (Dual Support)**
- **Goal**: Add property-based activation while maintaining `application-shell.yml` and `@Profile("shell")` compatibility
- **Files to modify**: `embabel-agent-shell/src/main/kotlin/com/embabel/agent/shell/config/ShellProperties.kt` (add dual support)
- **Files to create**: `ShellAutoConfiguration.kt` (with dual support logic for both property and profile activation)
- **Files to keep**: `application-shell.yml` (maintain for backward compatibility - **will be deprecated later**)
- **Profiles to keep**: `@Profile("shell")` annotations (maintain for backward compatibility - **will be deprecated later**)
- **Note**: Shell module already has configuration classes - extend existing structure
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Section 1](PROFILES_MIGRATION_GUIDE.md#1-shell-profile-migration)

### **Phase B: Personality Plugin Infrastructure (Iterations 3-8)**

**Iteration 3: Personality Property-Based Configuration (Dual Support)**
- **Goal**: Add `embabel.agent.logging.*` while maintaining profile activation compatibility
- **Files to create**: `PersonalityConfiguration.kt`
- **Files to modify**: All 5 personality classes (keep both `@Profile` and `@ConditionalOnProperty`)
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Section 0: Personality Profiles](PROFILES_MIGRATION_GUIDE.md#0-personality-profiles-migration)
- **Details**: See detailed section below

**Iteration 4: Property Integration & Validation**
- **Goal**: Robust property system with validation and fallback mechanisms
- **Details**: See detailed section below

**Iteration 5: Core Plugin Infrastructure**
- **Goal**: Registry and provider interfaces for dynamic personality management
- **Details**: See detailed section below

**Iteration 6: Provider Implementation Wrappers**
- **Goal**: Convert existing personalities to plugin providers
- **Details**: See detailed section below

**Iteration 7: Runtime Management & API**
- **Goal**: Dynamic personality switching capabilities
- **Details**: See detailed section below

**Iteration 8: Enhanced Dynamic Properties**
- **Goal**: Advanced property dynamism and external configuration support
- **Details**: See detailed section below

### **Phase C: Remaining Profile Migrations (Iterations 9-12)**

**Iteration 9: Neo4j Profile Migration (Dual Support)**
- **Goal**: Add `embabel.agent.infrastructure.neo4j.*` while maintaining `application-neo.yml` compatibility
- **Files to create**: `Neo4jConfiguration.kt`, `Neo4jAutoConfiguration.kt` (with dual support)
- **Files to keep**: `application-neo.yml` (maintain for backward compatibility)
- **Security**: Remove hardcoded credentials, require environment variables
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Section 2](PROFILES_MIGRATION_GUIDE.md#2-neo4j-profile-migration)

**Iteration 10: MCP Profiles Migration (Dual Support)**
- **Goal**: Add `embabel.agent.infrastructure.mcp.*` while maintaining existing profile files
- **Files to create**: `McpConfiguration.kt`, `McpAutoConfiguration.kt` (with dual support)
- **Files to keep**: `application-docker-ce.yml`, `application-docker-desktop.yml`
- **Security**: Externalize API keys (GITHUB_TOKEN, BRAVE_API_KEY, etc.)
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Section 3](PROFILES_MIGRATION_GUIDE.md#3-mcp-profiles-migration-docker-ce--docker-desktop)

**Iteration 11: Observability Profile Migration (Dual Support)**
- **Goal**: Add `embabel.agent.infrastructure.observability.*` while maintaining `application-observability.yml`
- **Files to create**: `ObservabilityConfiguration.kt`, `ObservabilityAutoConfiguration.kt` (with dual support)
- **Files to keep**: `application-observability.yml` (maintain for backward compatibility)
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Section 4](PROFILES_MIGRATION_GUIDE.md#4-observability-profile-migration)

**Iteration 12: Add Deprecation Warnings**
- **Goal**: Warn users about profile usage, guide to property-based config
- **Files to create**: `ProfileDeprecationWarner.kt`
- **Profiles to deprecate**: `@Profile("shell")`, `@Profile("neo")`, `@Profile("docker-ce")`, `@Profile("docker-desktop")`, `@Profile("observability")`, personality profiles
- **Files to deprecate**: `application-shell.yml`, `application-neo.yml`, `application-docker-ce.yml`, `application-docker-desktop.yml`, `application-observability.yml`
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Backward Compatibility](PROFILES_MIGRATION_GUIDE.md#backward-compatibility-strategy)

### **Phase D: Profile Deprecation & Cleanup (Iterations 13-14)**

**Iteration 13: Remove Profile Support**
- **Goal**: Remove all `@Profile` annotations and profile-based logic
- **Files to modify**: All configuration classes, remove dual support
- **Profiles to remove**: `@Profile("shell")`, `@Profile("neo")`, `@Profile("docker-ce")`, `@Profile("docker-desktop")`, `@Profile("observability")`, personality profiles
- **Reference**: [PROFILES_MIGRATION_GUIDE.md - Phase 3](PROFILES_MIGRATION_GUIDE.md#phase-3-profile-removal)

**Iteration 14: Delete Profile Files & Add Templates**
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

**Total Timeline**: 15 iterations (1 platform foundation + 1 platform @ConfigurationProperties + 1 shell + 6 personality + 4 remaining profiles + 2 cleanup)  

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

## Iteration 0: Platform Property Foundation

**Focus**: Establish proper property segregation between platform internals and application configuration

### Files to Create:
- `src/main/kotlin/com/embabel/agent/config/AgentPlatformProperties.kt`

### Files to Modify:
- `src/main/kotlin/com/embabel/agent/core/deployment/AgentScanningProperties.kt`
- `src/main/kotlin/com/embabel/agent/spi/support/LlmRanker.kt`
- `src/main/kotlin/com/embabel/agent/api/common/autonomy/Autonomy.kt`
- `src/main/kotlin/com/embabel/agent/spi/support/DefaultAgentProcessIdGenerator.kt`
- `src/main/kotlin/com/embabel/agent/spi/support/springai/ChatClientLlmOperations.kt`
- `src/main/kotlin/com/embabel/agent/spi/support/LlmDataBindingProperties.kt`
- `src/main/kotlin/com/embabel/agent/web/sse/SseController.kt`
- `src/main/kotlin/com/embabel/agent/config/models/AnthropicModels.kt`
- `src/main/kotlin/com/embabel/agent/core/support/DefaultAgentPlatform.kt`

### Changes:

**1. Create AgentPlatformProperties.kt:**
```kotlin
@ConfigurationProperties("embabel.agent.platform")
data class AgentPlatformProperties(
    // Core platform identity
    var name: String = "embabel-default",
    var description: String = "Embabel Default Agent Platform",
    
    // Platform behavior configurations
    var scanning: ScanningConfig = ScanningConfig(),
    var ranking: RankingConfig = RankingConfig(),
    var llmOperations: LlmOperationsConfig = LlmOperationsConfig(),
    var processIdGeneration: ProcessIdGenerationConfig = ProcessIdGenerationConfig(),
    var autonomy: AutonomyConfig = AutonomyConfig(),
    var models: ModelsConfig = ModelsConfig(),
    var sse: SseConfig = SseConfig(),
    var anthropic: AnthropicConfig = AnthropicConfig(),
    var test: TestConfig = TestConfig()
) {
    data class ScanningConfig(
        var annotation: Boolean = true,
        var bean: Boolean = false,
        var auto: Boolean = false
    )
    
    data class RankingConfig(
        var llm: String? = null,
        var maxAttempts: Int = 5,
        var backoffMillis: Long = 100L,
        var backoffMultiplier: Double = 5.0,
        var backoffMaxInterval: Long = 180000L
    )
    
    data class LlmOperationsConfig(
        var prompts: PromptsConfig = PromptsConfig(),
        var dataBinding: DataBindingConfig = DataBindingConfig()
    ) {
        data class PromptsConfig(
            var maybePromptTemplate: String = "maybe_prompt_contribution",
            var generateExamplesByDefault: Boolean = true
        )
        
        data class DataBindingConfig(
            var maxAttempts: Int = 10,
            var fixedBackoffMillis: Long = 30L
        )
    }
    
    data class ProcessIdGenerationConfig(
        var includeVersion: Boolean = false,
        var includeAgentName: Boolean = false
    )
    
    data class AutonomyConfig(
        var agentConfidenceCutOff: Double = 0.6,
        var goalConfidenceCutOff: Double = 0.6
    )
    
    data class ModelsConfig(
        var defaultLlm: String = "gpt-4.1-mini",
        var defaultEmbeddingModel: String = "text-embedding-3-small"
    )
    
    data class SseConfig(
        var maxBufferSize: Int = 100,
        var maxProcessBuffers: Int = 1000
    )
    
    data class AnthropicConfig(
        var maxAttempts: Int = 10,
        var backoffMillis: Long = 5000L,
        var backoffMultiplier: Double = 5.0,
        var backoffMaxInterval: Long = 180000L
    )
    
    data class TestConfig(
        var mockMode: Boolean = true
    )
}
```

**2. Consolidate existing properties under unified platform namespace:**

| Current Property | New Platform Property |
|------------------|----------------------|
| `embabel.agent-platform.name` | `embabel.agent.platform.name` |
| `embabel.agent-platform.description` | `embabel.agent.platform.description` |
| `embabel.agent-platform.scanning.*` | `embabel.agent.platform.scanning.*` |
| `embabel.agent-platform.ranking.*` | `embabel.agent.platform.ranking.*` |
| `embabel.autonomy.*` | `embabel.agent.platform.autonomy.*` |
| `embabel.process-id-generation.*` | `embabel.agent.platform.processIdGeneration.*` |
| `embabel.llm-operations.*` | `embabel.agent.platform.llmOperations.*` |
| `embabel.models.default-*` | `embabel.agent.platform.models.*` |
| `embabel.sse.*` | `embabel.agent.platform.sse.*` |
| `embabel.anthropic.*` | `embabel.agent.platform.anthropic.*` |

**3. Update @ConfigurationProperties annotations in existing classes:**

| File | Current Annotation | New Annotation |
|------|-------------------|----------------|
| `AgentScanningProperties.kt` | `@ConfigurationProperties("embabel.agent-platform.scanning")` | `@ConfigurationProperties("embabel.agent.platform.scanning")` |
| `LlmRanker.kt` (RankingProperties) | `@ConfigurationProperties("embabel.agent-platform.ranking")` | `@ConfigurationProperties("embabel.agent.platform.ranking")` |
| `Autonomy.kt` (AutonomyProperties) | `@ConfigurationProperties("embabel.autonomy")` | `@ConfigurationProperties("embabel.agent.platform.autonomy")` |
| `DefaultAgentProcessIdGenerator.kt` | `@ConfigurationProperties("embabel.process-id-generation")` | `@ConfigurationProperties("embabel.agent.platform.processIdGeneration")` |
| `ChatClientLlmOperations.kt` | `@ConfigurationProperties(prefix = "embabel.llm-operations.prompts")` | `@ConfigurationProperties("embabel.agent.platform.llmOperations.prompts")` |
| `LlmDataBindingProperties.kt` | `@ConfigurationProperties(prefix = "embabel.llm-operations.data-binding")` | `@ConfigurationProperties("embabel.agent.platform.llmOperations.dataBinding")` |
| `SseController.kt` (SseProperties) | `@ConfigurationProperties(prefix = "embabel.sse")` | `@ConfigurationProperties("embabel.agent.platform.sse")` |
| `AnthropicModels.kt` (AnthropicProperties) | `@ConfigurationProperties(prefix = "embabel.anthropic")` | `@ConfigurationProperties("embabel.agent.platform.anthropic")` |

**4. Update @Value annotations in DefaultAgentPlatform.kt:**
- `@Value("\${embabel.agent-platform.name:default-agent-platform}")` → `@Value("\${embabel.agent.platform.name:embabel-default}")`
- `@Value("\${embabel.agent-platform.description:Default Agent Platform}")` → `@Value("\${embabel.agent.platform.description:Embabel Default Agent Platform}")`

### Testing:
- **Core Properties**: `embabel.agent.platform.name=my-platform`
- **Scanning**: `embabel.agent.platform.scanning.annotation=true`
- **Ranking**: `embabel.agent.platform.ranking.maxAttempts=10`
- **Autonomy**: `embabel.agent.platform.autonomy.agentConfidenceCutOff=0.8`
- **LLM Operations**: `embabel.agent.platform.llmOperations.dataBinding.maxAttempts=15`
- **Models**: `embabel.agent.platform.models.defaultLlm=gpt-4`
- **Environment Variable Override**: `EMBABEL_AGENT_PLATFORM_SCANNING_ANNOTATION=false`
- **System Property Override**: `-Dembabel.agent.platform.test.mockMode=false`
- **Backward Compatibility**: Ensure existing `embabel.agent-platform.*` properties still work during transition

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
@ConditionalOnProperty("embabel.agent.platform.management.personalityUpdatesEnabled", havingValue = "true")
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
@ConditionalOnProperty("embabel.agent.platform.management.configRefreshEnabled", havingValue = "true")
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

---

## **APPENDICES: Implementation Details**

### **Appendix A: Iteration 0 - Platform Property Foundation**

#### **Overview**
Consolidate scattered platform properties under unified `embabel.agent.platform.*` namespace with automatic migration detection and warnings.

#### **Files to Create (6 files)**

**Kotlin Configuration Classes (4 files):**

| File | Package | Purpose |
|------|---------|---------|
| `AgentPlatformProperties.kt` | `com.embabel.agent.config` | Unified platform property configuration |
| `SimpleDeprecatedConfigurationWarner.kt` | `com.embabel.agent.config.migration` | Detects deprecated properties in all sources with aggregated logging |
| `ConditionalPropertyScanner.kt` | `com.embabel.agent.config.migration` | Scans for @ConditionalOnProperty with old names |
| `ConditionalPropertyScanningConfig.kt` | `com.embabel.agent.config.migration` | Configuration for conditional property scanning |

**Configuration Files (1 file - YAML imports properties):**

| File | Location | Purpose | Precedence |
|------|----------|---------|------------|
| `agent-platform.properties` | `src/main/resources/` | Platform property defaults (kebab-case) | **Primary source** |
| `agent-platform.yml` | `src/main/resources/` | YAML wrapper using spring.config.import | **Imports .properties** |

#### **Files to Modify (9 files)**

| File | Package | Current Annotation | New Annotation |
|------|---------|-------------------|----------------|
| `AgentScanningProperties.kt` | `com.embabel.agent.core.deployment` | `@ConfigurationProperties("embabel.agent-platform.scanning")` | `@ConfigurationProperties("embabel.agent.platform.scanning")` |
| `LlmRanker.kt` (RankingProperties) | `com.embabel.agent.spi.support` | `@ConfigurationProperties("embabel.agent-platform.ranking")` | `@ConfigurationProperties("embabel.agent.platform.ranking")` |
| `Autonomy.kt` (AutonomyProperties) | `com.embabel.agent.api.common.autonomy` | `@ConfigurationProperties("embabel.autonomy")` | `@ConfigurationProperties("embabel.agent.platform.autonomy")` |
| `DefaultAgentProcessIdGenerator.kt` | `com.embabel.agent.spi.support` | `@ConfigurationProperties("embabel.process-id-generation")` | `@ConfigurationProperties("embabel.agent.platform.process-id-generation")` |
| `ChatClientLlmOperations.kt` | `com.embabel.agent.spi.support.springai` | `@ConfigurationProperties(prefix = "embabel.llm-operations.prompts")` | `@ConfigurationProperties("embabel.agent.platform.llm-operations.prompts")` |
| `LlmDataBindingProperties.kt` | `com.embabel.agent.spi.support` | `@ConfigurationProperties(prefix = "embabel.llm-operations.data-binding")` | `@ConfigurationProperties("embabel.agent.platform.llm-operations.data-binding")` |
| `SseController.kt` (SseProperties) | `com.embabel.agent.web.sse` | `@ConfigurationProperties(prefix = "embabel.sse")` | `@ConfigurationProperties("embabel.agent.platform.sse")` |
| `AnthropicModels.kt` (AnthropicProperties) | `com.embabel.agent.config.models` | `@ConfigurationProperties(prefix = "embabel.anthropic")` | `@ConfigurationProperties("embabel.agent.platform.models.anthropic")` |
| `DefaultAgentPlatform.kt` | `com.embabel.agent.core.support` | `@Value("\${embabel.agent-platform.name:default-agent-platform}")` | `@Value("\${embabel.agent.platform.name:embabel-agent-platform-default}")` |

#### **Documentation Updates (2 files)**

| File | Changes |
|------|---------|
| `README.md` | Add Property Segregation Principle + Property Migration section |
| `PROFILES_MIGRATION_GUIDE.md` | Add Phase 0: Platform Property Consolidation section |

#### **Tests to Create (4 files)**

| Test File | Package | Purpose |
|-----------|---------|---------|
| `AgentPlatformPropertiesIntegrationTest.kt` | `com.embabel.agent.config` | Test unified property binding |
| `SimpleDeprecatedPropertyWarnerTest.kt` | `com.embabel.agent.config.migration` | Test property deprecation warnings |
| `ConditionalPropertyScannerTest.kt` | `com.embabel.agent.config.migration` | Test @ConditionalOnProperty scanning |
| `PropertyMigrationIntegrationTest.kt` | `com.embabel.agent.config.migration` | Test end-to-end migration behavior |

#### **Property Migration Mappings**

| Old Property Namespace | New Property Namespace | Example Properties |
|------------------------|------------------------|-------------------|
| `embabel.agent-platform.scanning.*` | `embabel.agent.platform.scanning.*` | `annotation=true`, `bean=false` |
| `embabel.agent-platform.ranking.*` | `embabel.agent.platform.ranking.*` | `max-attempts=5`, `backoff-millis=100` |
| `embabel.autonomy.*` | `embabel.agent.platform.autonomy.*` | `agent-confidence-cut-off=0.6` |
| `embabel.process-id-generation.*` | `embabel.agent.platform.process-id-generation.*` | `include-version=false` |
| `embabel.llm-operations.prompts.*` | `embabel.agent.platform.llm-operations.prompts.*` | `maybe-prompt-template="maybe_prompt_contribution"` |
| `embabel.llm-operations.data-binding.*` | `embabel.agent.platform.llm-operations.data-binding.*` | `max-attempts=10`, `fixed-backoff-millis=30` |
| `embabel.sse.*` | `embabel.agent.platform.sse.*` | `max-buffer-size=100` |
| `embabel.anthropic.*` | `embabel.agent.platform.models.anthropic.*` | `max-attempts=10`, `backoff-millis=5000` |

#### **Configuration File Contents**

**`agent-platform.properties`:**
```properties
# Agent Platform Default Configuration
# These properties control internal platform behavior

# Agent Scanning Configuration
embabel.agent.platform.scanning.annotation=true
embabel.agent.platform.scanning.bean=false

# Ranking Configuration  
embabel.agent.platform.ranking.max-attempts=5
embabel.agent.platform.ranking.backoff-millis=100
embabel.agent.platform.ranking.backoff-multiplier=5.0
embabel.agent.platform.ranking.backoff-max-interval=180000

# Autonomy Configuration
embabel.agent.platform.autonomy.agent-confidence-cut-off=0.6
embabel.agent.platform.autonomy.goal-confidence-cut-off=0.6

# Process ID Generation Configuration
embabel.agent.platform.process-id-generation.include-version=false
embabel.agent.platform.process-id-generation.include-agent-name=false

# LLM Operations Configuration
embabel.agent.platform.llm-operations.prompts.maybe-prompt-template=maybe_prompt_contribution
embabel.agent.platform.llm-operations.prompts.generate-examples-by-default=true
embabel.agent.platform.llm-operations.data-binding.max-attempts=10
embabel.agent.platform.llm-operations.data-binding.fixed-backoff-millis=30

# SSE Configuration
embabel.agent.platform.sse.max-buffer-size=100
embabel.agent.platform.sse.max-process-buffers=1000

# Model Provider Configurations
embabel.agent.platform.models.anthropic.max-attempts=10
embabel.agent.platform.models.anthropic.backoff-millis=5000
embabel.agent.platform.models.anthropic.backoff-multiplier=5.0
embabel.agent.platform.models.anthropic.backoff-max-interval=180000

# Test Configuration
embabel.agent.platform.test.mock-mode=true
```

**`agent-platform.yml`:**
```yaml
# Agent Platform Default Configuration (YAML Format)
# This file imports the properties file to maintain single source of truth

spring:
  config:
    import:
      - classpath:agent-platform.properties

# Optional: YAML-specific overrides can be added here if needed
# embabel:
#   agent:
#     platform:
#       # Any YAML-specific customizations would go here
#       # But generally, keep everything in agent-platform.properties
```

#### **Environment Variable Updates**

| Old Environment Variable | New Environment Variable |
|---------------------------|--------------------------|
| `EMBABEL_AGENT_PLATFORM_SCANNING_ANNOTATION` | `EMBABEL_AGENT_PLATFORM_SCANNING_ANNOTATION` |
| `EMBABEL_AUTONOMY_AGENT_CONFIDENCE_CUT_OFF` | `EMBABEL_AGENT_PLATFORM_AUTONOMY_AGENT_CONFIDENCE_CUT_OFF` |
| `EMBABEL_PROCESS_ID_GENERATION_INCLUDE_VERSION` | `EMBABEL_AGENT_PLATFORM_PROCESS_ID_GENERATION_INCLUDE_VERSION` |
| `EMBABEL_LLM_OPERATIONS_DATA_BINDING_MAX_ATTEMPTS` | `EMBABEL_AGENT_PLATFORM_LLM_OPERATIONS_DATA_BINDING_MAX_ATTEMPTS` |
| `EMBABEL_SSE_MAX_BUFFER_SIZE` | `EMBABEL_AGENT_PLATFORM_SSE_MAX_BUFFER_SIZE` |
| `EMBABEL_ANTHROPIC_MAX_ATTEMPTS` | `EMBABEL_AGENT_PLATFORM_MODELS_ANTHROPIC_MAX_ATTEMPTS` |

#### **Implementation Strategy**

1. **Create configuration files** - `agent-platform.properties` (primary) and `agent-platform.yml` (imports properties)
2. **Create AgentPlatformProperties.kt** - Unified configuration class that binds to new namespace
3. **Update existing @ConfigurationProperties** - Change annotation prefixes to new namespace (9 files)
4. **Add migration detection components** - Create warning system for deprecated property usage
5. **Create comprehensive test suite** - Unit tests for each component + integration tests
6. **Update documentation** - README.md and PROFILES_MIGRATION_GUIDE.md with migration instructions
7. **Validate migration** - Ensure old properties are detected, new properties work correctly

#### **Validation Criteria**

- ✅ All platform properties consolidated under `embabel.agent.platform.*`
- ✅ Migration warnings detect user customizations of deprecated properties  
- ✅ No breaking changes for users who don't customize platform properties
- ✅ Clear migration path documented for users who do customize platform properties
- ✅ Comprehensive test coverage for property binding and migration detection
- ✅ Environment variables follow new naming convention

#### **Risk Mitigation**

- **Backward Compatibility**: Old properties are ignored (no crashes), new properties work correctly
- **User Notification**: Clear warnings about deprecated properties with exact migration instructions
- **Testing Strategy**: Both unit and integration tests cover migration scenarios
- **Documentation**: Complete migration guide with examples and troubleshooting

#### **Implementation Order (Recommended Sequence)**

**Phase 1: Foundation (Low Risk)**
1. Create `agent-platform.properties` and `agent-platform.yml` 
2. Create `AgentPlatformProperties.kt` class
3. Create basic integration test `AgentPlatformPropertiesIntegrationTest.kt`
4. Test that new properties bind correctly

**Phase 2: Migration Detection (Safety Net)**
5. Create `ConditionalScanningConfig.kt`
6. Create `SimpleDeprecatedPropertyWarner.kt` 
7. Create `ConditionalPropertyScanner.kt`
8. Create migration component tests
9. Test migration warnings work correctly

**Phase 3: Property Updates (Systematic)**
10. Update `AgentScanningProperties.kt` annotation
11. Update `LlmRanker.kt` (RankingProperties) annotation
12. Update `Autonomy.kt` (AutonomyProperties) annotation  
13. Update `DefaultAgentProcessIdGenerator.kt` annotation
14. Update `ChatClientLlmOperations.kt` annotation
15. Update `LlmDataBindingProperties.kt` annotation
16. Update `SseController.kt` (SseProperties) annotation
17. Update `AnthropicModels.kt` (AnthropicProperties) annotation
18. Update `DefaultAgentPlatform.kt` @Value annotations

**Phase 4: Integration & Validation**
19. Create `PropertyMigrationIntegrationTest.kt`
20. Run full test suite - ensure no regressions
21. Test with both old and new property configurations
22. Validate migration warnings appear correctly

**Phase 5: Documentation**
23. Update README.md with Property Segregation Principle
24. Update PROFILES_MIGRATION_GUIDE.md with Phase 0 section
25. Final documentation review

#### **Success Metrics**

- ✅ All 6 new files created with correct content
- ✅ All 9 property classes updated with new annotations
- ✅ Migration detection components provide helpful warnings
- ✅ No test failures in existing property-dependent tests  
- ✅ Documentation updated with clear migration instructions
- ✅ Zero breaking changes for non-customizing users
- ✅ Both properties and YAML configuration formats supported

#### **Quality Gates**

**After Phase 1:** New platform properties bind correctly from both file formats
**After Phase 2:** Migration warnings detect deprecated properties accurately  
**After Phase 3:** All existing functionality works with new property namespaces
**After Phase 4:** Integration tests pass, old properties are properly ignored
**After Phase 5:** Documentation provides clear migration path

#### **Rollback Plan**

If issues arise:
1. **Phase 1-2**: Safe to rollback - no existing code changed yet
2. **Phase 3**: Revert annotation changes one by one in reverse order
3. **Phase 4-5**: Remove new files, revert documentation changes

The systematic approach minimizes risk and allows for easy rollback at each phase.

---

## APPENDIX: MIGRATION PROGRESS

### Table of Contents

**[Appendix A: Iteration 0 - Platform Property Foundation](#appendix-a-iteration-0---platform-property-foundation)** ✅ COMPLETED  
**[Appendix B: Iteration 1 - Platform Property @ConfigurationProperties Migration](#appendix-b-iteration-1---platform-property-configurationproperties-migration)** 🔄 NEXT

---

### Appendix A: Iteration 0 - Platform Property Foundation

**🎉 STATUS: COMPLETED**

**Goal**: Establish proper property segregation between platform internals and application configuration with automated migration detection.

#### **Completed Deliverables**

**Phase 1: Platform Foundation (Steps 1-4)**  
✅ **agent-platform.properties** - Comprehensive platform defaults with kebab-case naming  
✅ **agent-platform.yml** - Import-based YAML configuration maintaining single source of truth  
✅ **AgentPlatformProperties.kt** - Unified configuration class with complete platform sections  
✅ **AgentPlatformPropertiesIntegrationTest.kt** - Full test coverage for property binding  

**Phase 2: Migration Detection System (Steps 5-8)**  
✅ **ConditionalScanningConfig.kt** - Configurable package scanning with 60+ framework exclusions  
✅ **SimpleDeprecatedPropertyWarner.kt** - Rate-limited warning system for deprecated usage  
✅ **ConditionalPropertyScanner.kt** - Automated scanning with extensible regex-based migration rules  
✅ **Complete Test Suite** - Unit and integration tests for entire migration system  

**Phase 3: Platform Property Enhancement (Step 9)**  
✅ **OpenAI Platform Support** - Added complete OpenAI model provider platform properties  
✅ **Migration Rule Updates** - Enhanced detection for both Anthropic and OpenAI migrations  

#### **Platform Property Structure Implemented**

**`embabel.agent.platform.*` namespace:**
```properties
# Agent Internal Configuration
embabel.agent.platform.scanning.annotation=true
embabel.agent.platform.ranking.max-attempts=5
embabel.agent.platform.autonomy.agent-confidence-cut-off=0.6
embabel.agent.platform.process-id-generation.include-version=false
embabel.agent.platform.llm-operations.prompts.maybe-prompt-template=maybe_prompt_contribution
embabel.agent.platform.llm-operations.data-binding.max-attempts=10

# Model Provider Integration (Platform Concerns)
embabel.agent.platform.models.anthropic.max-attempts=10
embabel.agent.platform.models.anthropic.backoff-millis=5000
embabel.agent.platform.models.openai.max-attempts=10
embabel.agent.platform.models.openai.backoff-millis=5000

# Platform Infrastructure
embabel.agent.platform.sse.max-buffer-size=100
embabel.agent.platform.test.mock-mode=true
```

#### **Migration Detection System Features**

- **Spring Startup Integration**: Automatically scans application classes during startup
- **Smart Package Filtering**: Excludes 60+ framework packages, focuses on user code  
- **Pattern-Based Rules**: Extensible regex transformation for property migration
- **Rate-Limited Warnings**: One warning per deprecated item per application run

#### **Files Created (8 files)**

**Production Code (6 files):**
- `src/main/resources/agent-platform.properties`
- `src/main/resources/agent-platform.yml` 
- `src/main/kotlin/com/embabel/agent/config/AgentPlatformProperties.kt`
- `src/main/kotlin/com/embabel/agent/config/migration/ConditionalScanningConfig.kt`
- `src/main/kotlin/com/embabel/agent/config/migration/SimpleDeprecatedPropertyWarner.kt`
- `src/main/kotlin/com/embabel/agent/config/migration/ConditionalPropertyScanner.kt`

**Test Code (4 files):**
- `src/test/kotlin/com/embabel/agent/config/AgentPlatformPropertiesIntegrationTest.kt`
- `src/test/kotlin/com/embabel/agent/config/migration/ConditionalPropertyScanningConfigIntegrationTest.kt`
- `src/test/kotlin/com/embabel/agent/config/migration/SimpleDeprecatedPropertyWarnerTest.kt`
- `src/test/kotlin/com/embabel/agent/config/migration/ConditionalPropertyScannerTest.kt`
- `src/test/kotlin/com/embabel/agent/config/migration/PlatformPropertiesMigrationIntegrationTest.kt`

#### **System Benefits**

**For Library Users:**
- **Automatic Detection**: No manual searching for deprecated properties
- **Clear Guidance**: Specific recommendations for each deprecated property  
- **Zero Configuration**: Works out-of-the-box with sensible defaults
- **Non-Intrusive**: Warnings only, doesn't break existing functionality

**For Framework Development:**  
- **Property Segregation**: Clear separation between platform internals and application config
- **Extensible Rules**: Easy to add new migration patterns without code changes
- **Comprehensive Coverage**: Platform property foundation ready for all future migrations
- **Future-Proof**: Foundation supports all subsequent platform property updates

#### **Post-Implementation Fixes**

**Issue**: Migration components caused Spring ApplicationContext loading failures during integration tests.

**Root Cause**: `@Component` classes (`SimpleDeprecatedConfigWarner`, `ConditionalPropertyScanningConfig`) were instantiated during all Spring context loading, including tests, causing circular dependency issues.

**Permanent Solution Applied:**

**File 1: `SimpleDeprecatedConfigWarner.kt`**
```kotlin
// BEFORE (temporary workaround):
@ConditionalOnProperty(
    name = ["embabel.agent.platform.test.mock-mode"],
    havingValue = "false",
    matchIfMissing = false
)

// AFTER (permanent solution):
@ConditionalOnProperty(
    name = ["embabel.agent.platform.migration.warnings.enabled"],
    havingValue = "true",
    matchIfMissing = true  // Enabled by default in production
)
```

**File 2: `ConditionalPropertyScanningConfig.kt`**
```kotlin
// BEFORE (temporary workaround):
@ConditionalOnProperty(
    name = ["embabel.agent.platform.test.mock-mode"],
    havingValue = "false",
    matchIfMissing = false
)

// AFTER (permanent solution):
@ConditionalOnProperty(
    name = ["embabel.agent.platform.migration.scanning.enabled"],
    havingValue = "true",
    matchIfMissing = false  // Disabled by default (Iteration 0 design)
)
```

**Configuration Control:**
```yaml
# Production - Enable migration warnings (default behavior)
embabel:
  agent:
    platform:
      migration:
        warnings:
          enabled: true  # Default via matchIfMissing=true

# Production - Conditional scanning disabled by default in Iteration 0  
        scanning:
          enabled: false  # Explicit disable (matchIfMissing=false)

# Tests - Can override for specific migration tests
        scanning:
          enabled: true  # Override in migration-specific tests only
```

**Benefits of Permanent Solution:**
- ✅ **Proper Separation**: Migration components controlled by appropriate configuration properties
- ✅ **Production Ready**: Warnings enabled by default for production users  
- ✅ **Test Isolation**: Integration tests no longer have Spring context loading issues
- ✅ **Iteration 0 Design**: Conditional scanning remains disabled by default as planned
- ✅ **Migration Test Support**: Migration-specific tests can enable scanning when needed
- ✅ **Future Flexibility**: Clear configuration path for enabling features in later iterations

**Impact on Iteration 0 Deliverables:**
- Migration warning system works in production ✅
- Migration detection tests continue to pass ✅  
- Integration tests no longer fail due to context loading ✅
- All original Iteration 0 goals achieved ✅

---

### Appendix B: Iteration 1 - Platform Property @ConfigurationProperties Migration

**🔄 STATUS: PLANNED (NEXT ITERATION)**

**Goal**: Update model provider @ConfigurationProperties prefixes to use new platform namespace established in Iteration 0.

#### **Scope**

**Concrete Changes (2 @ConfigurationProperties updates):**

**File 1: `AnthropicModels.kt:36`**
```kotlin
// BEFORE:
@ConfigurationProperties(prefix = "embabel.anthropic")
data class AnthropicProperties(...)

// AFTER:
@ConfigurationProperties(prefix = "embabel.agent.platform.models.anthropic")
data class AnthropicProperties(...)
```

**File 2: `OpenAiModels.kt:31`**
```kotlin
// BEFORE:
@ConfigurationProperties(prefix = "embabel.openai")
data class OpenAiProperties(...)

// AFTER:
@ConfigurationProperties(prefix = "embabel.agent.platform.models.openai")
data class OpenAiProperties(...)
```

#### **Migration Detection**

**Automatic Warnings**: The migration detection system from Iteration 0 will automatically detect and warn users about:
- Usage of deprecated `embabel.anthropic.*` properties
- Usage of deprecated `embabel.openai.*` properties  
- Provide specific recommendations for migration to new platform namespace

#### **Implementation Strategy**

1. **Update @ConfigurationProperties prefixes** in both model provider files
2. **Test property binding** - Ensure properties work with new namespace
3. **Validate migration warnings** - Confirm detection system warns about old prefixes
4. **Update any related documentation** - Property examples and migration guide
5. **Verify backward compatibility** - Old properties ignored, new properties work

#### **Quality Gates**

- ✅ Model provider properties bind correctly with new platform namespace
- ✅ Migration detection system warns about deprecated prefixes  
- ✅ No breaking changes for users not customizing model provider properties
- ✅ Clear warnings guide users to correct property names
- ✅ Tests pass for both old (ignored) and new (working) property configurations

#### **Risk Assessment**

**Low Risk**: These changes affect only @ConfigurationProperties prefixes. Users who don't customize model provider retry settings will see no impact. Users who do customize will get clear warnings and migration guidance.

#### **Success Criteria**

- Model provider beans continue to function normally
- Custom model provider retry configurations work with new property names  
- Deprecated property usage generates helpful warnings
- Documentation reflects new property structure
- Zero functional regressions in model provider behavior