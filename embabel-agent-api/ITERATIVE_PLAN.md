# Personality Plugin Infrastructure - Final Implementation Plan

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
- [Future Iterations](#future-iterations)

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

**Rationale:**
- **Clear separation** between framework vs agent configuration packages
- **Module independence** maintained (shell stays in shell module)
- **Logical grouping** by functional area (logging, infrastructure, models)
- **Matches property hierarchy** in package naming

## Complete Implementation Sequence

### **Phase A: Foundation & Core Migrations (Iterations 0-2)**

**Iteration 0: Platform Property Foundation** ✅ COMPLETED
- **Goal**: Establish proper property segregation between platform internals and application configuration
- **Files**: 6 new + 9 modified + documentation updates
- **Migration**: Consolidate existing `embabel.agent-platform.*` properties under `embabel.agent.platform.*`
- **Details**: [Appendix A: Iteration 0 Implementation Details](#appendix-a-iteration-0---platform-property-foundation)

**Iteration 1: Platform Property @ConfigurationProperties Migration** 🔄 NEXT
- **Goal**: Update model provider @ConfigurationProperties prefixes to use new platform namespace
- **Files to modify**: `AnthropicModels.kt`, `OpenAiModels.kt` (2 @ConfigurationProperties prefix updates)
- **Migration**: `embabel.anthropic.*` → `embabel.agent.platform.models.anthropic.*`, `embabel.openai.*` → `embabel.agent.platform.models.openai.*`
- **Detection**: Existing migration detection system will automatically warn users about deprecated usage
- **Details**: [Appendix B: Iteration 1 Implementation Details](#appendix-b-iteration-1---platform-property-configurationproperties-migration)

**Iteration 2: Shell Profile Migration (Dual Support)**
- **Goal**: Add property-based activation while maintaining `application-shell.yml` and `@Profile("shell")` compatibility
- **Files to modify**: `embabel-agent-shell/src/main/kotlin/com/embabel/agent/shell/config/ShellProperties.kt` (add dual support)
- **Files to create**: `ShellAutoConfiguration.kt` (with dual support logic for both property and profile activation)
- **Files to keep**: `application-shell.yml` (maintain for backward compatibility)
- **Profiles to keep**: `@Profile("shell")` annotations (maintain for backward compatibility)

### **Phase B: Personality Plugin Infrastructure (Iterations 3-8)**

**Iteration 3: Personality Property-Based Configuration (Dual Support)**
- **Goal**: Add `embabel.agent.logging.*` while maintaining profile activation compatibility
- **Files to create**: `PersonalityConfiguration.kt`
- **Files to modify**: All 5 personality classes (keep both `@Profile` and `@ConditionalOnProperty`)

**Iteration 4: Property Integration & Validation**
- **Goal**: Robust property system with validation and fallback mechanisms

**Iteration 5: Core Plugin Infrastructure**
- **Goal**: Registry and provider interfaces for dynamic personality management

**Iteration 6: Provider Implementation Wrappers**
- **Goal**: Convert existing personalities to plugin providers

**Iteration 7: Runtime Management & API**
- **Goal**: Dynamic personality switching capabilities

**Iteration 8: Enhanced Dynamic Properties**
- **Goal**: Advanced property dynamism and external configuration support

### **Phase C: Remaining Profile Migrations (Iterations 9-12)**

**Iteration 9: Neo4j Profile Migration (Dual Support)**
- **Goal**: Add `embabel.agent.infrastructure.neo4j.*` while maintaining `application-neo.yml` compatibility
- **Files to create**: `Neo4jConfiguration.kt`, `Neo4jAutoConfiguration.kt` (with dual support)
- **Files to keep**: `application-neo.yml` (maintain for backward compatibility)
- **Security**: Remove hardcoded credentials, require environment variables

**Iteration 10: MCP Profiles Migration (Dual Support)**
- **Goal**: Add `embabel.agent.infrastructure.mcp.*` while maintaining existing profile files
- **Files to create**: `McpConfiguration.kt`, `McpAutoConfiguration.kt` (with dual support)
- **Files to keep**: `application-docker-ce.yml`, `application-docker-desktop.yml`
- **Security**: Externalize API keys (GITHUB_TOKEN, BRAVE_API_KEY, etc.)

**Iteration 11: Observability Profile Migration (Dual Support)**
- **Goal**: Add `embabel.agent.infrastructure.observability.*` while maintaining `application-observability.yml`
- **Files to create**: `ObservabilityConfiguration.kt`, `ObservabilityAutoConfiguration.kt` (with dual support)
- **Files to keep**: `application-observability.yml` (maintain for backward compatibility)

**Iteration 12: Add Deprecation Warnings**
- **Goal**: Warn users about profile usage, guide to property-based config
- **Files to create**: `ProfileDeprecationWarner.kt`
- **Profiles to deprecate**: All profile-based configurations

### **Phase D: Profile Deprecation & Cleanup (Iterations 13-14)**

**Iteration 13: Remove Profile Support**
- **Goal**: Remove all `@Profile` annotations and profile-based logic
- **Files to modify**: All configuration classes, remove dual support

**Iteration 14: Delete Profile Files & Add Templates**
- **Goal**: Remove all `application-{profile}.yml` files and provide property-based templates
- **Files to delete**: All profile-specific configuration files
- **Files to create**: Application configuration templates

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

**Total Timeline**: 14 iterations (1 platform foundation + 1 platform @ConfigurationProperties + 1 shell + 6 personality + 4 remaining profiles + 2 cleanup)

---

## Detailed Implementation Sections

### Iteration 3: Personality Property-Based Configuration (Dual Support)

**Focus**: Replace Spring profile dependencies with property-based activation for personalities

#### Files to Create:
- `src/main/kotlin/com/embabel/agent/config/agent/logging/PersonalityConfiguration.kt`

#### Files to Modify:
- `src/main/kotlin/com/embabel/agent/event/logging/personality/starwars/StarWarsLoggingAgenticEventListener.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/severance/SeveranceLoggingAgenticEventListener.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/hitchhiker/HitchhikerLoggingAgenticEventListener.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/montypython/MontyPythonLoggingAgenticEventListener.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/colossus/ColossusLoggingAgenticEventListener.kt`

#### Implementation:

**1. Create PersonalityConfiguration.kt:**
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

**2. Update personality classes (example with StarWars):**
```kotlin
@Component
@ConditionalOnProperty(
    name = ["embabel.agent.logging.personality"],
    havingValue = "starwars"
)
@Profile("personality-starwars")  // Keep for backward compatibility
class StarWarsLoggingAgenticEventListener : LoggingAgenticEventListener {
    // Implementation unchanged
}
```

---

### Iteration 4: Property Integration & Validation

**Focus**: Robust property system with validation and fallback mechanisms

#### Files to Create:
- `src/main/kotlin/com/embabel/agent/config/validation/PersonalityConfigurationValidator.kt`

#### Implementation:

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
    
    companion object {
        private val logger = LoggerFactory.getLogger(PersonalityConfigurationValidator::class.java)
    }
}
```

---

### Iteration 5: Core Plugin Infrastructure

**Focus**: Registry and provider interfaces for dynamic personality management

#### Files to Create:
- `src/main/kotlin/com/embabel/agent/event/logging/PersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/PersonalityProviderRegistry.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/BasePersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/PersonalityChangedEvent.kt`

#### Implementation:

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

**2. PersonalityProviderRegistry:**
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
    
    private fun registerProvider(provider: PersonalityProvider) {
        providers[provider.name] = provider
        logger.debug("Registered personality provider: ${provider.name}")
    }
    
    private fun activatePersonality(personalityName: String): LoggingAgenticEventListener? {
        val provider = providers[personalityName]
        return provider?.let {
            activePersonality = it.createEventListener()
            logger.info("Activated personality: $personalityName")
            activePersonality
        }
    }
    
    fun switchPersonality(name: String): Boolean {
        return try {
            val newPersonality = activatePersonality(name)
            if (newPersonality != null) {
                personalityConfig.personality = name
                applicationContext.publishEvent(PersonalityChangedEvent(name, newPersonality))
                logger.info("Switched to personality: $name")
                true
            } else {
                logger.warn("Failed to switch to personality: $name - provider not found")
                false
            }
        } catch (e: Exception) {
            logger.error("Failed to switch to personality: $name", e)
            false
        }
    }
    
    fun getActivePersonality(): LoggingAgenticEventListener? = activePersonality
    fun getAvailablePersonalities(): Set<String> = providers.keys.toSet()
    
    companion object {
        private val logger = LoggerFactory.getLogger(PersonalityProviderRegistry::class.java)
    }
}
```

**3. BasePersonalityProvider:**
```kotlin
abstract class BasePersonalityProvider : PersonalityProvider {
    protected abstract fun createSpecificEventListener(): LoggingAgenticEventListener
    
    final override fun createEventListener(): LoggingAgenticEventListener {
        return if (isAvailable()) {
            createSpecificEventListener()
        } else {
            throw IllegalStateException("Personality $name is not available")
        }
    }
    
    companion object {
        protected val logger = LoggerFactory.getLogger(BasePersonalityProvider::class.java)
    }
}
```

**4. PersonalityChangedEvent:**
```kotlin
data class PersonalityChangedEvent(
    val personalityName: String,
    val eventListener: LoggingAgenticEventListener
) : ApplicationEvent(personalityName)
```

---

### Iteration 6: Provider Implementation Wrappers

**Focus**: Convert existing personalities to plugin providers

#### Files to Create:
- `src/main/kotlin/com/embabel/agent/event/logging/personality/starwars/StarWarsPersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/severance/SeverancePersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/hitchhiker/HitchhikerPersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/montypython/MontyPythonPersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/colossus/ColossusPersonalityProvider.kt`
- `src/main/kotlin/com/embabel/agent/event/logging/personality/DefaultPersonalityProvider.kt`

#### Implementation (example pattern):

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

---

### Iteration 7: Runtime Management & API

**Focus**: Dynamic personality switching capabilities

#### Files to Create:
- `src/main/kotlin/com/embabel/agent/web/rest/PersonalityManagementController.kt`
- `src/main/kotlin/com/embabel/agent/web/rest/PersonalityInfo.kt`
- `src/main/kotlin/com/embabel/agent/web/rest/PersonalityMetadata.kt`
- `src/main/kotlin/com/embabel/agent/web/rest/ApiResponse.kt`

#### Implementation:

**1. PersonalityManagementController:**
```kotlin
@RestController
@RequestMapping("/api/personality")
@ConditionalOnProperty("embabel.agent.platform.management.personalityUpdatesEnabled", havingValue = "true")
class PersonalityManagementController(
    private val personalityRegistry: PersonalityProviderRegistry
) {
    
    @GetMapping("/current")
    fun getCurrentPersonality(): PersonalityInfo {
        val activePersonality = personalityRegistry.getActivePersonality()
        return PersonalityInfo(
            name = activePersonality?.let { "current" } ?: "none",
            description = "Currently active personality",
            active = activePersonality != null
        )
    }
    
    @GetMapping("/available")
    fun getAvailablePersonalities(): Map<String, PersonalityMetadata> {
        return personalityRegistry.getAvailablePersonalities().associateWith { name ->
            PersonalityMetadata(
                name = name,
                description = "Personality: $name",
                version = "1.0.0",
                author = "Embabel"
            )
        }
    }
    
    @PostMapping("/switch/{name}")
    fun switchPersonality(@PathVariable name: String): ResponseEntity<ApiResponse> {
        val success = personalityRegistry.switchPersonality(name)
        return if (success) {
            ResponseEntity.ok(ApiResponse(true, "Successfully switched to personality: $name"))
        } else {
            ResponseEntity.badRequest().body(ApiResponse(false, "Failed to switch to personality: $name"))
        }
    }
    
    @PostMapping("/reload")
    fun reloadPersonalities(): ResponseEntity<ApiResponse> {
        return try {
            personalityRegistry.initialize()
            ResponseEntity.ok(ApiResponse(true, "Personalities reloaded successfully"))
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse(false, "Failed to reload personalities: ${e.message}"))
        }
    }
}
```

**2. Data classes:**
```kotlin
data class PersonalityInfo(
    val name: String,
    val description: String,
    val active: Boolean
)

data class PersonalityMetadata(
    val name: String,
    val description: String,
    val version: String,
    val author: String
)

data class ApiResponse(
    val success: Boolean,
    val message: String
)
```

---

### Iteration 8: Enhanced Dynamic Properties

**Focus**: Advanced property dynamism and external configuration support

#### Files to Create:
- `src/main/kotlin/com/embabel/agent/config/ExternalConfigurationLoader.kt`
- `src/main/kotlin/com/embabel/agent/config/PersonalityConfigurationRefresher.kt`
- `src/main/resources/META-INF/spring-configuration-metadata.json`

#### Implementation:

**1. ExternalConfigurationLoader:**
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
    
    private fun loadPersonalityConfigFromDirectory(dir: Path) {
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try {
                val configFile = dir.resolve("personality.properties")
                if (Files.exists(configFile)) {
                    logger.info("Loading external personality configuration from: $configFile")
                    // Implementation would load properties from file
                }
            } catch (e: Exception) {
                logger.warn("Failed to load configuration from directory: $dir", e)
            }
        }
    }
    
    companion object {
        private val logger = LoggerFactory.getLogger(ExternalConfigurationLoader::class.java)
    }
}
```

**2. PersonalityConfigurationRefresher:**
```kotlin
@Component
@ConditionalOnProperty("embabel.agent.platform.management.configRefreshEnabled", havingValue = "true")
class PersonalityConfigurationRefresher(
    private val personalityRegistry: PersonalityProviderRegistry,
    private val environment: Environment
) {
    
    @EventListener
    fun handleConfigurationRefresh(event: EnvironmentChangeEvent) {
        if (event.keys.contains("embabel.agent.logging.personality")) {
            val newPersonality = environment.getProperty("embabel.agent.logging.personality", "default")
            personalityRegistry.switchPersonality(newPersonality)
        }
    }
    
    companion object {
        private val logger = LoggerFactory.getLogger(PersonalityConfigurationRefresher::class.java)
    }
}
```

**3. Configuration metadata for IDE support:**
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

---

## Future Iterations

### **Phase E: Model Provider Plugin Infrastructure (Future)**

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
- ⏳ Personality Plugin Infrastructure (Iterations 3-8) - **serves as reference implementation**
- ⏳ Profile Migration Complete (Iterations 9-14)

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

---

## APPENDICES: Implementation Details

### **Appendix A: Iteration 0 - Platform Property Foundation**

**🎉 STATUS: COMPLETED**

**Goal**: Establish proper property segregation between platform internals and application configuration with automated migration detection.

#### **Completed Deliverables**

**Phase 1: Platform Foundation**  
✅ **agent-platform.properties** - Comprehensive platform defaults with kebab-case naming  
✅ **agent-platform.yml** - Import-based YAML configuration maintaining single source of truth  
✅ **AgentPlatformProperties.kt** - Unified configuration class with complete platform sections  
✅ **AgentPlatformPropertiesIntegrationTest.kt** - Full test coverage for property binding

**Phase 2: Migration Detection System**  
✅ **ConditionalScanningConfig.kt** - Configurable package scanning with 60+ framework exclusions  
✅ **SimpleDeprecatedPropertyWarner.kt** - Rate-limited warning system for deprecated usage  
✅ **ConditionalPropertyScanner.kt** - Automated scanning with extensible regex-based migration rules  
✅ **Complete Test Suite** - Unit and integration tests for entire migration system

**Phase 3: Platform Property Enhancement**  
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

---

### **Appendix B: Iteration 1 - Platform Property @ConfigurationProperties Migration**

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

#### **Success Criteria**

- Model provider beans continue to function normally
- Custom model provider retry configurations work with new property names
- Deprecated property usage generates helpful warnings
- Documentation reflects new property structure
- Zero functional regressions in model provider behavior

---

## Required Imports for All Files

```kotlin
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.annotation.PostConstruct
import javax.validation.constraints.Pattern
```

---

This implementation plan provides the complete, detailed roadmap for transforming the personality system into a modern, property-based plugin architecture while maintaining full backward compatibility throughout the transition period.