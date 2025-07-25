# Personality Plugin Infrastructure - Detailed Iterative Implementation Plan

## Overview

Transform the personality system from profile-based to property-based activation with plugin architecture support. This plan focuses specifically on the personality system as a test case for the broader library-centric transformation.

## Implementation Strategy

**Primary Goal**: Property-based configuration foundation before plugin infrastructure  
**Approach**: Incremental commits with backward compatibility  
**Timeline**: 6 iterations, each representing 1 commit  

---

## Iteration 1: Property-Based Configuration Foundation

**Focus**: Replace Spring profile dependencies with property-based activation across framework

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

**2. Update personality classes (example - StarWars):**
```kotlin
// REMOVE:
@Profile("starwars")

// ADD:
@ConditionalOnProperty(
    name = "embabel.agent.logging.personality", 
    havingValue = "starwars"
)
```

**3. Apply same pattern to all 5 personality classes**

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