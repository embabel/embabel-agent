# Embabel Agent Framework - Phase 1 Detailed Implementation Plan

## Project Overview

**Objective:** Transform embabel-agent-api from application-centric to library-centric highly configurable design using Spring Framework with transitional strategy for profile removal. Both model providers and personalities use the same extensible, declarative plugin architecture

**Timeline:** 5 weeks × 18 hours/week = 98 total hours (includes YAML generation)  
**Approach:** Conservative, minimal structural changes, dual configuration support, incremental commits  
**Profile Strategy:** No deprecation in Phase 1 (delayed to Phase 2 due to auto-config dependencies)
**Shell Encapsulation:** Complete shell independence - NO shell references in embabel-agent-api
**Commit Strategy:** Step-by-step incremental commits for safe, reviewable progress

---

## Incremental Commit Plan

### **Commit 1: Core Framework Properties Foundation**
**Deliverable:** Basic AgentFrameworkProperties structure  
**Files Changed:**
- `src/main/kotlin/com/embabel/agent/config/AgentFrameworkProperties.kt` (NEW)
- `src/main/kotlin/com/embabel/agent/config/AgentPlatformConfiguration.kt` (MODIFIED - add @EnableConfigurationProperties)

**Content:**
```kotlin
// AgentFrameworkProperties.kt - Framework internals only
@ConfigurationProperties(prefix = "embabel.framework")
data class AgentFrameworkProperties(
    @NestedConfigurationProperty
    val logging: LoggingConfig = LoggingConfig(),
    
    @NestedConfigurationProperty
    val scanning: ScanningConfig = ScanningConfig(),
    
    @NestedConfigurationProperty
    val test: TestConfig = TestConfig()
    
    // NO models, NO autonomy, NO features - application concerns
)
```

**Test:** Verify properties load correctly, no breaking changes

### **Commit 2: Model Provider Property-Based Activation**
**Deliverable:** Replace profile checks with @ConditionalOnProperty in model providers  
**Files Changed:**
- `src/main/kotlin/com/embabel/agent/config/models/BedrockModels.kt` (MODIFIED)
- `src/main/kotlin/com/embabel/agent/config/models/OllamaModels.kt` (MODIFIED)
- `src/main/kotlin/com/embabel/agent/config/models/OpenAiModels.kt` (MODIFIED)

**Content:**
```kotlin
// Before: if (!environment.activeProfiles.contains(BEDROCK_PROFILE)) return
// After: @ConditionalOnProperty("embabel.agent.models.bedrock.enabled", havingValue = "true", matchIfMissing = true)
@Configuration
@ConditionalOnProperty("embabel.agent.models.bedrock.enabled", havingValue = "true", matchIfMissing = true)
class BedrockModels {
    @PostConstruct
    fun registerModels() {
        // Remove profile check, keep all existing logic
    }
}
```

**Test:** All existing profile-based apps work, new property-based activation works

### **Commit 3: Personality Plugin System Foundation (with Builder)**
**Deliverable:** Plugin architecture interfaces, registry, and builder pattern  
**Files Changed:**
- `src/main/kotlin/com/embabel/agent/event/logging/personality/PersonalityProviderPlugin.kt` (NEW)
- `src/main/kotlin/com/embabel/agent/event/logging/personality/PersonalityProviderRegistry.kt` (NEW)
- `src/main/kotlin/com/embabel/agent/event/logging/personality/PersonalityBuilder.kt` (NEW)

**Content:**
```kotlin
// PersonalityProviderPlugin.kt
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Component
annotation class PersonalityProvider(val name: String, val priority: Int = 0)

interface PersonalityProviderPlugin {
    fun createPersonality(): LoggingPersonality
    fun createColorPalette(): ColorPalette? = null
}

// PersonalityBuilder.kt
interface PersonalityBuilder {
    fun name(name: String): PersonalityBuilder
    fun loggingMessages(config: LoggingConfig): PersonalityBuilder
    fun displayTheme(config: DisplayConfig): PersonalityBuilder  // For shell module
    fun colorPalette(palette: ColorPalette): PersonalityBuilder
    fun build(): PersonalityProviderPlugin
}

// PersonalityProviderRegistry.kt
@Component
class PersonalityProviderRegistry(private val plugins: List<PersonalityProviderPlugin>) {
    fun getPersonality(name: String): LoggingPersonality?
    fun getColorPalette(name: String): ColorPalette?
    fun builder(): PersonalityBuilder = PersonalityBuilderImpl()
}
```

**Test:** Registry loads, builder creates valid plugins, no impact on existing functionality

### **Commit 4: Built-in Personality Plugins**
**Deliverable:** Convert existing personalities to plugin system  
**Files Changed:**
- `src/main/kotlin/com/embabel/agent/event/logging/personality/builtin/StarWarsPersonalityPlugin.kt` (NEW)
- `src/main/kotlin/com/embabel/agent/event/logging/personality/builtin/SeverancePersonalityPlugin.kt` (NEW)
- `src/main/kotlin/com/embabel/agent/event/logging/personality/builtin/HitchhikerPersonalityPlugin.kt` (NEW)
- `src/main/kotlin/com/embabel/agent/event/logging/personality/builtin/ColossusPersonalityPlugin.kt` (NEW)
- `src/main/kotlin/com/embabel/agent/event/logging/personality/builtin/MontyPythonPersonalityPlugin.kt` (NEW)

**Content:**
```kotlin
@PersonalityProvider("starwars")
class StarWarsPersonalityPlugin : PersonalityProviderPlugin {
    override fun createPersonality(): LoggingPersonality = StarWarsPersonality()
    override fun createColorPalette(): ColorPalette = StarWarsColorPalette
}
```

**Test:** All built-in personalities discoverable via registry

### **Commit 5: Property-Based Personality Configuration**
**Deliverable:** PersonalityConfiguration with property-based selection  
**Files Changed:**
- `src/main/kotlin/com/embabel/agent/event/logging/personality/PersonalityConfiguration.kt` (NEW)

**Content:**
```kotlin
@Configuration
class PersonalityConfiguration {
    @Bean
    @Primary
    @ConditionalOnProperty("embabel.agent.logging.personality")
    fun personalityFromProperties(
        properties: AgentFrameworkProperties,
        registry: PersonalityProviderRegistry
    ): LoggingPersonality {
        return registry.getPersonality(properties.logging.personality!!)
            ?: registry.getPersonality("default") ?: DefaultPersonality()
    }
    
    // Keep existing @Profile beans for backward compatibility
    @Bean
    @Profile("starwars")
    @ConditionalOnMissingBean(LoggingPersonality::class)
    fun starWarsProfile(): LoggingPersonality = StarWarsPersonality()
}
```

**Test:** Property-based personality works, profile fallback works

### **Commit 6: Declarative Palette Configuration**
**Deliverable:** Properties-based palette definitions  
**Files Changed:**
- `src/main/resources/palettes.properties` (NEW)

**Content:**
```properties
# StarWars palette
starwars.palette.primary=#FFE81F
starwars.palette.secondary=#00F5FF
starwars.palette.success=#00FF00
starwars.palette.error=#FF0000

# Severance palette  
severance.palette.primary=#4A90E2
severance.palette.secondary=#F5A623
severance.palette.success=#7ED321
severance.palette.error=#D0021B
```

**Test:** Palettes load from properties, fallback to programmatic works

### **Commit 7: A2A Feature Configuration**
**Deliverable:** Property-based A2A configuration  
**Files Changed:**
- `src/main/kotlin/com/embabel/agent/config/FeatureConfiguration.kt` (NEW)

**Content:**
```kotlin
@Configuration
class FeatureConfiguration {
    @Bean
    @Primary
    @ConditionalOnProperty("embabel.agent.features.a2a.enabled", havingValue = "true")
    fun a2aConfigurationFromProperties(properties: AgentFrameworkProperties): A2AConfiguration {
        val a2aConfig = properties.features.a2a
        return A2AConfiguration(enabled = true, port = a2aConfig.port, host = a2aConfig.host)
    }
    
    @Bean
    @Profile("a2a")
    @ConditionalOnMissingBean(A2AConfiguration::class)
    fun a2aFromProfile(): A2AConfiguration = A2AConfiguration(enabled = true)
}
```

**Test:** A2A works via properties, profile fallback works

### **Commit 8: Property Validation Framework**
**Deliverable:** Validation for framework properties  
**Files Changed:**
- `src/main/kotlin/com/embabel/agent/config/PropertyValidation.kt` (NEW)

**Content:**
```kotlin
@Component
@ConfigurationPropertiesBinding
class AgentFrameworkPropertiesValidator : Validator {
    override fun validate(target: Any, errors: Errors) {
        val properties = target as AgentFrameworkProperties
        
        // Validate personality, A2A configuration, etc.
    }
}
```

**Test:** Invalid properties rejected, valid properties accepted

### **Commit 9: Comprehensive Test Suite**
**Deliverable:** Full test coverage for dual configuration  
**Files Changed:**
- `src/test/kotlin/com/embabel/agent/config/AgentFrameworkPropertiesTest.kt` (NEW)
- `src/test/kotlin/com/embabel/agent/config/DualConfigurationTest.kt` (NEW)
- `src/test/kotlin/com/embabel/agent/config/PersonalityPluginTest.kt` (NEW)
- `src/test/resources/application-test.yml` (NEW)

**Test:** All configuration combinations work, precedence correct

### **Commit 10: Documentation and Examples**
**Deliverable:** Updated configuration documentation  
**Files Changed:**
- `README.md` (MODIFIED)
- `docs/CONFIGURATION.md` (NEW)
- `docs/MIGRATION_EXAMPLES.md` (NEW)

**Content:** Configuration guide, migration patterns, plugin documentation

**Test:** Documentation accurate, examples work

## Commit Benefits:
✅ **Each commit is independently testable**  
✅ **Incremental progress - can stop at any commit**  
✅ **Easy to review - focused changes**  
✅ **Safe rollback - minimal blast radius**  
✅ **CI/CD friendly - fast feedback**  
✅ **Team collaboration - parallel work possible**

---

## Module Responsibility Clarification

### **embabel-agent-api (Core Framework)**
- **Prefix:** `embabel.agent.*`
- **Scope:** Core framework, logging, models, A2A, autonomy
- **NO shell references** - shell is completely independent

### **embabel-agent-shell (Shell Module)**  
- **Prefix:** `embabel.shell.*`
- **Scope:** Shell UI, terminal services, commands
- **Independent configuration** - managed within shell module

---

## Week 1: Foundation Setup (21 hours)

### Task 1A: Create AgentFrameworkProperties (8 hours)

#### Deliverables:
- `AgentFrameworkProperties.kt` - **FRAMEWORK INTERNALS ONLY**
- Framework-specific configuration classes
- **NO application-level properties** - applications manage their own
- Unit tests

#### Framework Properties Scope:
**Include (Framework Internals):**
- `embabel.framework.scanning.*` - Bean registration and scanning
- `embabel.framework.llm-operations.*` - LLM operation internals
- `embabel.framework.process-id-generation.*` - Internal ID generation
- `embabel.framework.sse.*` - Server-sent events configuration
- Model provider retry/backoff settings

**Exclude (Application Responsibility):**
- Confidence thresholds, model selection, API keys
- Business logic configuration
- Application-specific timeouts/limits

#### Implementation:

**File: `src/main/kotlin/com/embabel/agent/config/AgentFrameworkProperties.kt`**
```kotlin
/*
 * Copyright 2024-2025 Embabel Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty

@ConfigurationProperties(prefix = "embabel.agent")
data class AgentFrameworkProperties(
    @NestedConfigurationProperty
    val logging: LoggingConfig = LoggingConfig(),
    
    @NestedConfigurationProperty
    val models: ModelConfig = ModelConfig(),
    
    @NestedConfigurationProperty
    val features: FeatureConfig = FeatureConfig(),
    
    @NestedConfigurationProperty
    val autonomy: AutonomyConfig = AutonomyConfig()
) {
    
    data class LoggingConfig(
        /**
         * Logging personality: starwars, severance, hitchhiker, colossus, montypython
         * When set, takes precedence over spring.profiles.active
         */
        val personality: String? = null,
        
        /**
         * Logging verbosity level: quiet, normal, verbose, debug
         */
        val verbosity: String = "normal",
        
        /**
         * Custom color palette override (optional)
         */
        val colorPalette: String? = null
    )
    
    data class ModelConfig(
        /**
         * Model provider: openai, anthropic, bedrock, ollama, mock
         * When set, takes precedence over spring.profiles.active
         */
        val provider: String? = null,
        
        /**
         * API key for the model provider (can use ${ENV_VAR} syntax)
         */
        val apiKey: String? = null,
        
        /**
         * Custom base URL for model provider
         */
        val baseUrl: String? = null,
        
        @NestedConfigurationProperty
        val openai: OpenAiConfig = OpenAiConfig(),
        
        @NestedConfigurationProperty
        val anthropic: AnthropicConfig = AnthropicConfig(),
        
        @NestedConfigurationProperty
        val test: TestConfig = TestConfig(),
        
        @NestedConfigurationProperty
        val production: ProductionConfig = ProductionConfig()
    )
    
    data class TestConfig(
        /**
         * Enable mock mode in tests (default: true for safety)
         */
        val mockMode: Boolean = true,
        
        /**
         * Simulate failures in mock providers
         */
        val simulateFailures: Boolean = false,
        
        /**
         * Allow real providers in test profile (default: false for safety)
         */
        val allowRealProviders: Boolean = false
    )
    
    data class ProductionConfig(
        /**
         * Validate API keys are present for production providers
         */
        val validateApiKeys: Boolean = true,
        
        /**
         * Required environment name for production validation
         */
        val requiredEnvironment: String? = null
    )
    
    data class OpenAiConfig(
        val apiKey: String? = null,
        val baseUrl: String? = null,
        val organization: String? = null,
        val model: String = "gpt-4"
    )
    
    data class AnthropicConfig(
        val apiKey: String? = null,
        val model: String = "claude-3-sonnet-20240229",
        val maxTokens: Int = 4096
    )
    
    data class FeatureConfig(
        @NestedConfigurationProperty
        val a2a: A2AConfig = A2AConfig(),
        
        @NestedConfigurationProperty
        val observability: ObservabilityConfig = ObservabilityConfig()
        
        // NOTE: Shell configuration is handled entirely by embabel-agent-shell module
        // NO shell configuration here - complete module independence
    )
    
    data class A2AConfig(
        /**
         * Enable Agent-to-Agent communication
         * When true, takes precedence over @Profile("a2a")
         */
        val enabled: Boolean = false,
        val port: Int = 8080,
        val host: String = "localhost"
    )
    
    data class ObservabilityConfig(
        /**
         * Enable observability features
         * When true, takes precedence over @Profile("observability")
         */
        val enabled: Boolean = false,
        val metricsEnabled: Boolean = true,
        val tracingEnabled: Boolean = true
    )
    
    data class AutonomyConfig(
        /**
         * Confidence cutoff for goal selection (0.0 - 1.0)
         * DEFAULT: Applications should override this value
         */
        val goalConfidenceCutOff: Double = 0.7,
        
        /**
         * Confidence cutoff for agent selection (0.0 - 1.0)
         * DEFAULT: Applications should override this value
         */
        val agentConfidenceCutOff: Double = 0.7,
        
        /**
         * Maximum retries for failed operations
         * DEFAULT: Applications should override this value
         */
        val maxRetries: Int = 3,
        
        /**
         * Enable goal selection optimization
         * DEFAULT: Applications should override this value
         */
        val enableGoalSelection: Boolean = true,
        
        /**
         * Enable agent selection optimization
         * DEFAULT: Applications should override this value
         */
        val enableAgentSelection: Boolean = true
    )
}
```

#### Task 1A Time Breakdown:
- **2 hours**: Design property structure (NO shell references)
- **3 hours**: Implement property classes with documentation
- **2 hours**: Create unit tests for property binding
- **1 hour**: Integration with existing configuration

#### Task 1D Time Breakdown:
- **1 hour**: Design test/production configuration strategy
- **1 hour**: Implement test-aware property configuration
- **1 hour**: Create test safety validation and documentation

#### Task 1A Testing:
```kotlin
// File: src/test/kotlin/com/embabel/agent/config/AgentFrameworkPropertiesTest.kt
@SpringBootTest
@TestPropertySource(properties = [
    "embabel.agent.logging.personality=starwars",
    "embabel.agent.models.provider=openai",
    "embabel.agent.features.a2a.enabled=true"
    // NOTE: No shell properties tested here - shell module handles its own tests
])
class AgentFrameworkPropertiesTest {
    
    @Autowired
    private lateinit var properties: AgentFrameworkProperties
    
    @Test
    fun `should load core framework properties correctly`() {
        assertThat(properties.logging.personality).isEqualTo("starwars")
        assertThat(properties.models.provider).isEqualTo("openai")
        assertThat(properties.features.a2a.enabled).isTrue()
        // No shell assertions - shell is independent
    }
    
    @Test
    fun `should not have shell configuration in core framework`() {
        // Verify shell encapsulation - no shell properties accessible from core
        val featureConfig = properties.features
        // featureConfig should NOT have shell property
    }
    
    @Test
    fun `should use default values when properties not set`() {
        val defaultProps = AgentFrameworkProperties()
        assertThat(defaultProps.logging.verbosity).isEqualTo("normal")
        assertThat(defaultProps.autonomy.goalConfidenceCutOff).isEqualTo(0.7)
        assertThat(defaultProps.features.a2a.enabled).isFalse()
    }
}
```

### Task 1B: Enable Properties in Main Configuration (6 hours)

#### Deliverables:
- Modified `AgentPlatformConfiguration.kt`
- Property validation
- Configuration documentation

#### Implementation:

**File: `src/main/kotlin/com/embabel/agent/config/AgentPlatformConfiguration.kt`** (Modified)
```kotlin
@Configuration
@EnableConfigurationProperties(AgentFrameworkProperties::class)  // ADD THIS LINE
@ComponentScan(
    basePackages = [
        "com.embabel.agent.api",
        "com.embabel.agent.core",
        "com.embabel.agent.domain",
        "com.embabel.agent.event",
        "com.embabel.agent.support",
        "com.embabel.agent.validation",
        "com.embabel.agent.spi",
        "com.embabel.agent.tools"
        // NOTE: NO "com.embabel.agent.shell" - shell module is independent
    ]
)
class AgentPlatformConfiguration {
    // Existing configuration remains unchanged
    // Just add @EnableConfigurationProperties annotation
    // NO shell-related beans - shell module handles its own configuration
}
```

#### Task 1B Time Breakdown:
- **2 hours**: Add @EnableConfigurationProperties integration
- **2 hours**: Validate property loading in existing configuration
- **2 hours**: Create integration tests (excluding shell)

### Task 1C: Basic Property Validation (4 hours)

#### Deliverables:
- Property validation tests
- YAML configuration examples (core framework only)
- Error handling for invalid properties

### Task 1D: Test Profile Strategy with Exclusion Patterns (3 hours)

#### Deliverables:
- Test configuration properties with safety checks
- @Profile("!test") pattern preservation  
- Test-aware property-based configuration
- Production safety validation

#### Problem Statement:
The library must handle both `@Profile("test")` and `@Profile("!test")` patterns that are critical for:
- **Production beans**: `@Profile("!test")` ensures they don't activate in tests
- **Test beans**: `@Profile("test")` provides test-specific overrides
- **Safety**: Preventing accidental real API calls during testing

#### Bean Creation Priority:
1. **@Primary Property-based** (highest) - works in any profile with safety checks
2. **@Profile("test")** (medium) - test-specific overrides
3. **@ConditionalOnMissingBean** (fallback) - default beans for all other environments

#### Implementation:

**Phase 1 Model Provider Changes:**

Individual model provider classes will be updated to use property-based activation instead of profile checks.

**Configuration Examples:**
```yaml
# Enable specific model providers
embabel:
  agent:
    models:
      bedrock:
        enabled: true
      ollama:
        enabled: true
      openai:
        enabled: false  # Disable OpenAI if not needed
```

**Test Configuration Examples:**

**File: `src/test/resources/application-test.yml`** (Enhanced)
```yaml
# Test profile configuration
spring:
  profiles:
    active: test

embabel:
  agent:
    models:
      bedrock:
        enabled: true
      ollama:
        enabled: false  # Disable in tests
```

---

## Week 2: Dual Configuration Support (18 hours)

### Task 2A: Personality Dual Configuration (8 hours)

#### Deliverables:
- Modified `PersonalityConfiguration.kt`
- Property-based personality selection
- Precedence testing

#### Implementation:

**Personality Plugin System (New)**

**File: `src/main/kotlin/com/embabel/agent/event/logging/personality/PersonalityProviderPlugin.kt`** (New)
```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Component
annotation class PersonalityProvider(
    val name: String,
    val priority: Int = 0
)

interface PersonalityProviderPlugin {
    fun createPersonality(): LoggingPersonality
    fun createColorPalette(): ColorPalette? = null
}
```

**File: `src/main/kotlin/com/embabel/agent/event/logging/personality/PersonalityProviderRegistry.kt`** (New)
```kotlin
@Component
class PersonalityProviderRegistry(
    private val plugins: List<PersonalityProviderPlugin>
) {
    private val pluginsByName = plugins.associateBy { 
        it.javaClass.getAnnotation(PersonalityProvider::class.java).name 
    }
    
    fun getPersonality(name: String): LoggingPersonality? {
        return pluginsByName[name]?.createPersonality()
    }
    
    fun getColorPalette(name: String): ColorPalette? {
        return pluginsByName[name]?.createColorPalette()
    }
    
    fun getAvailablePersonalities(): List<String> {
        return pluginsByName.keys.toList()
    }
}
```

**File: `src/main/kotlin/com/embabel/agent/event/logging/personality/PersonalityConfiguration.kt`** (New)
```kotlin
@Configuration
class PersonalityConfiguration {
    
    companion object {
        private val logger = LoggerFactory.getLogger(PersonalityConfiguration::class.java)
    }
    
    // NEW: Property-based personality selection (HIGHEST PRIORITY)
    @Bean
    @Primary
    @ConditionalOnProperty("embabel.agent.logging.personality")
    fun personalityFromProperties(
        properties: AgentFrameworkProperties,
        registry: PersonalityProviderRegistry
    ): LoggingPersonality {
        val personalityName = properties.logging.personality!!
        logger.info("Creating personality '{}' from properties", personalityName)
        
        return registry.getPersonality(personalityName)
            ?: run {
                logger.warn("Unknown personality '{}', falling back to default", personalityName)
                registry.getPersonality("default") ?: DefaultPersonality()
            }
    }
    
    // NEW: Property-based color palette selection
    @Bean
    @Primary
    @ConditionalOnProperty("embabel.agent.logging.colorPalette")
    fun colorPaletteFromProperties(
        properties: AgentFrameworkProperties,
        registry: PersonalityProviderRegistry
    ): ColorPalette {
        val paletteName = properties.logging.colorPalette!!
        logger.info("Creating color palette '{}' from properties", paletteName)
        
        return registry.getColorPalette(paletteName)
            ?: run {
                logger.warn("Unknown color palette '{}', using default", paletteName)
                DefaultColorPalette
            }
    }
    
    // EXISTING: Profile-based personality selection (FALLBACK)
    // All existing @Profile beans remain UNCHANGED for backward compatibility
    
    @Bean
    @Profile("starwars")
    @ConditionalOnMissingBean(LoggingPersonality::class)
    fun starWarsProfile(): LoggingPersonality {
        logger.info("Creating StarWars personality from profile")
        return StarWarsPersonality()
    }
    
    // ... other existing @Profile beans unchanged
}
```

**Built-in Personality Plugins:**

**File: `src/main/kotlin/com/embabel/agent/event/logging/personality/builtin/StarWarsPersonalityPlugin.kt`** (New)
```kotlin
@PersonalityProvider("starwars")
class StarWarsPersonalityPlugin : PersonalityProviderPlugin {
    override fun createPersonality(): LoggingPersonality {
        return StarWarsPersonality()
    }
    
    override fun createColorPalette(): ColorPalette {
        return StarWarsColorPalette
    }
}
```

**File: `src/main/kotlin/com/embabel/agent/event/logging/personality/builtin/SeverancePersonalityPlugin.kt`** (New)
```kotlin
@PersonalityProvider("severance")
class SeverancePersonalityPlugin : PersonalityProviderPlugin {
    override fun createPersonality(): LoggingPersonality {
        return SeverancePersonality()
    }
    
    override fun createColorPalette(): ColorPalette {
        return LumonColorPalette
    }
}
```

**Declarative Palette Configuration:**

**File: `src/main/resources/palettes.properties`** (New)
```properties
# StarWars palette
starwars.palette.primary=#FFE81F
starwars.palette.secondary=#00F5FF
starwars.palette.success=#00FF00
starwars.palette.error=#FF0000
starwars.palette.warning=#FFFF00

# Severance palette
severance.palette.primary=#4A90E2
severance.palette.secondary=#F5A623
severance.palette.success=#7ED321
severance.palette.error=#D0021B
severance.palette.warning=#F8E71C
```

**Future Extension Example (Third-Party):**
```kotlin
// Third-party jar: corporate-personality.jar
@PersonalityProvider("corporate")
class CorporatePersonalityPlugin : PersonalityProviderPlugin {
    override fun createPersonality(): LoggingPersonality {
        return CorporatePersonality()
    }
    
    override fun createColorPalette(): ColorPalette {
        return CorporateColorPalette
    }
}
```

### Task 2A Time Breakdown:
- **3 hours**: Implement property-based personality selection
- **2 hours**: Add @Primary and conditional logic
- **2 hours**: Create comprehensive tests
- **1 hour**: Logging and error handling

### Task 2B: Model Configuration Property Migration (6 hours)

#### Deliverables:
- Modified existing model configuration classes
- Property-based activation instead of profile checks
- Backward compatibility maintained

#### Implementation:

**File: `src/main/kotlin/com/embabel/agent/config/models/BedrockModels.kt`** (Modified)
```kotlin
@Configuration
@ConditionalOnProperty("embabel.agent.models.bedrock.enabled", havingValue = "true", matchIfMissing = true)
class BedrockModels {
    // Existing constructor and dependencies unchanged
    
    @PostConstruct
    fun registerModels() {
        // Remove: if (!environment.activeProfiles.contains(BEDROCK_PROFILE)) return
        
        // Existing registration logic unchanged
        if (!isBedrockConfigured()) {
            logger.warn("Bedrock misconfigured, no Bedrock models available.")
            return
        }
        
        // ... existing model registration logic remains the same
    }
    
    // All other existing methods remain unchanged
}
```

**Configuration Examples:**
```yaml
# Enable specific model providers
embabel:
  agent:
    models:
      bedrock:
        enabled: true
      ollama:
        enabled: true
      openai:
        enabled: false  # Disable OpenAI if not needed
```

---

## Week 3: Testing & Validation (18 hours)

### Task 3A: Dual Configuration Testing (12 hours)

#### Deliverables:
- Comprehensive test suite
- Precedence validation
- Integration tests (core framework only)

#### Implementation:

**Test Configuration Examples:**

**File: `src/test/resources/application-test.yml`** (Enhanced)
```yaml
# Test profile configuration
spring:
  profiles:
    active: test

embabel:
  agent:
    logging:
      personality: starwars
      verbosity: debug
    models:
      provider: mock  # Explicit mock for tests
      test:
        mockMode: true
        simulateFailures: false
        allowRealProviders: false  # Safety: prevent real API calls
    features:
      a2a:
        enabled: false  # Typically disabled in tests
      observability:
        enabled: true
        metricsEnabled: false  # Reduce test noise
    autonomy:
      goalConfidenceCutOff: 0.5  # Lower threshold for tests
      maxRetries: 1  # Faster test execution

# Shell configuration (separate namespace - handled by shell module)
embabel:
  shell:
    enabled: false  # Usually disabled in unit tests
```

**File: `src/test/resources/application-integration-test.yml`**
```yaml
# Integration test profile - allows real providers if needed
spring:
  profiles:
    active: test

embabel:
  agent:
    models:
      provider: openai
      test:
        allowRealProviders: true  # Explicit override for integration tests
      openai:
        apiKey: ${OPENAI_TEST_API_KEY}  # Separate test API key
```

**File: `src/test/kotlin/com/embabel/agent/config/PropertyValidationTest.kt`**
```kotlin
@SpringBootTest
class PropertyValidationTest {
    
    @Test
    fun `should validate personality values`() {
        val validPersonalities = setOf("starwars", "severance", "hitchhiker", "colossus", "montypython", "default")
        // Test validation logic
    }
    
    @Test
    fun `should validate confidence cutoff ranges`() {
        // Test 0.0 <= confidence <= 1.0
    }
    
    @Test  
    fun `should handle missing optional properties gracefully`() {
        // Test default values work correctly
    }
    
    @Test
    fun `should not interfere with shell module configuration`() {
        // Verify core framework properties don't conflict with shell module
        // Test with both embabel.agent.* and embabel.shell.* properties
    }
}
```

**File: `src/test/resources/application-test.yml`**
```yaml
# Core framework configuration only
embabel:
  agent:
    logging:
      personality: starwars
      verbosity: debug
    models:
      provider: openai
      openai:
        apiKey: test-key
        model: gpt-4
    features:
      a2a:
        enabled: true
        port: 8081
      observability:
        enabled: true
    autonomy:
      goalConfidenceCutOff: 0.8
      agentConfidenceCutOff: 0.75

# Shell configuration (separate namespace - handled by shell module)
embabel:
  shell:
    enabled: true
    lineLength: 140
    chat:
      confirmGoals: true
      bindConversation: false
```

---

## Week 2: Dual Configuration Support (18 hours)

### Task 2A: Personality Dual Configuration (8 hours)

#### Deliverables:
- Modified `PersonalityConfiguration.kt`
- Property-based personality selection
- Precedence testing

#### Implementation:

**File: `src/main/kotlin/com/embabel/agent/event/logging/personality/PersonalityConfiguration.kt`** (Modified)
```kotlin
@Configuration
class PersonalityConfiguration {
    
    companion object {
        private val logger = LoggerFactory.getLogger(PersonalityConfiguration::class.java)
    }
    
    // NEW: Property-based personality selection (HIGHEST PRIORITY)
    @Bean
    @Primary
    @ConditionalOnProperty("embabel.agent.logging.personality")
    fun personalityFromProperties(
        properties: AgentFrameworkProperties
    ): LoggingPersonality {
        val personalityName = properties.logging.personality!!
        logger.info("Creating personality '{}' from properties", personalityName)
        
        return when (personalityName.lowercase()) {
            "starwars" -> StarWarsPersonality()
            "severance" -> SeverancePersonality() 
            "hitchhiker" -> HitchhikerPersonality()
            "colossus" -> ColossusPersonality()
            "montypython" -> MontyPythonPersonality()
            "default" -> DefaultPersonality()
            else -> {
                logger.warn("Unknown personality '{}', falling back to default", personalityName)
                DefaultPersonality()
            }
        }
    }
    
    // NEW: Property-based color palette selection
    @Bean
    @Primary
    @ConditionalOnProperty("embabel.agent.logging.colorPalette")
    fun colorPaletteFromProperties(
        properties: AgentFrameworkProperties
    ): ColorPalette {
        val paletteName = properties.logging.colorPalette!!
        logger.info("Creating color palette '{}' from properties", paletteName)
        
        return when (paletteName.lowercase()) {
            "starwars" -> StarWarsColorPalette
            "severance", "lumon" -> LumonColorPalette
            "hitchhiker" -> HitchhikerColorPalette
            "colossus" -> ColossusColorPalette
            "montypython" -> MontyPythonColorPalette
            else -> {
                logger.warn("Unknown color palette '{}', using default", paletteName)
                DefaultColorPalette
            }
        }
    }
    
    // EXISTING: Profile-based personality selection (FALLBACK)
    // All existing @Profile beans remain UNCHANGED
    
    @Bean
    @Profile("starwars")
    @ConditionalOnMissingBean(LoggingPersonality::class)
    fun starWarsProfile(): LoggingPersonality {
        logger.info("Creating StarWars personality from profile")
        return StarWarsPersonality()
    }
    
    @Bean
    @Profile("severance") 
    @ConditionalOnMissingBean(LoggingPersonality::class)
    fun severanceProfile(): LoggingPersonality {
        logger.info("Creating Severance personality from profile")
        return SeverancePersonality()
    }
    
    @Bean
    @Profile("hitchhiker")
    @ConditionalOnMissingBean(LoggingPersonality::class) 
    fun hitchhikerProfile(): LoggingPersonality {
        logger.info("Creating Hitchhiker personality from profile")
        return HitchhikerPersonality()
    }
    
    @Bean
    @Profile("colossus")
    @ConditionalOnMissingBean(LoggingPersonality::class)
    fun colossusProfile(): LoggingPersonality {
        logger.info("Creating Colossus personality from profile")
        return ColossusPersonality()
    }
    
    @Bean
    @Profile("montypython")
    @ConditionalOnMissingBean(LoggingPersonality::class)
    fun montyPythonProfile(): LoggingPersonality {
        logger.info("Creating MontyPython personality from profile")
        return MontyPythonPersonality()
    }
    
    // DEFAULT: When neither property nor profile is set
    @Bean
    @ConditionalOnMissingBean(LoggingPersonality::class)
    fun defaultPersonality(): LoggingPersonality {
        logger.info("Creating default personality (no property or profile specified)")
        return DefaultPersonality()
    }
}
```

#### Task 2A Time Breakdown:
- **3 hours**: Implement property-based personality selection
- **2 hours**: Add @Primary and conditional logic
- **2 hours**: Create comprehensive tests
- **1 hour**: Logging and error handling

### Task 2B: Model Configuration Property Migration (6 hours)

#### Deliverables:
- Modified existing model configuration classes
- Property-based activation instead of profile checks
- Backward compatibility maintained

#### Implementation:

**File: `src/main/kotlin/com/embabel/agent/config/models/BedrockModels.kt`** (Modified)
```kotlin
@Configuration
@ConditionalOnProperty("embabel.agent.models.bedrock.enabled", havingValue = "true", matchIfMissing = true)
class BedrockModels {
    // Existing constructor and dependencies unchanged
    
    @PostConstruct
    fun registerModels() {
        // Remove: if (!environment.activeProfiles.contains(BEDROCK_PROFILE)) return
        
        // Existing registration logic unchanged
        if (!isBedrockConfigured()) {
            logger.warn("Bedrock misconfigured, no Bedrock models available.")
            return
        }
        
        // ... existing model registration logic remains the same
    }
    
    // All other existing methods remain unchanged
}
```

**File: `src/main/kotlin/com/embabel/agent/config/models/OllamaModels.kt`** (Modified)
```kotlin
@Configuration
@ConditionalOnProperty("embabel.agent.models.ollama.enabled", havingValue = "true", matchIfMissing = true)
class OllamaModels {
    // Existing constructor and dependencies unchanged
    
    @PostConstruct
    fun registerModels() {
        // Remove: if (!environment.activeProfiles.contains(OLLAMA_PROFILE)) return
        
        // Existing registration logic unchanged
        logger.info("Ollama models will be discovered at {}", baseUrl)
        
        // ... existing model registration logic remains the same
    }
    
    // All other existing methods remain unchanged
}
```

**Configuration Examples:**
```yaml
# Enable specific model providers
embabel:
  agent:
    models:
      bedrock:
        enabled: true
      ollama:
        enabled: true
      openai:
        enabled: false  # Disable OpenAI if not needed
```

### Task 2C: Feature Configuration Dual Support (4 hours)

#### Deliverables:
- Feature toggle support via properties (A2A and observability only)
- NO shell configuration (handled by shell module)

#### Implementation:

**File: `src/main/kotlin/com/embabel/agent/config/FeatureConfiguration.kt`** (New)
```kotlin
@Configuration
class FeatureConfiguration {
    
    // NEW: Property-based A2A configuration
    @Bean
    @Primary
    @ConditionalOnProperty("embabel.agent.features.a2a.enabled", havingValue = "true")
    fun a2aConfigurationFromProperties(
        properties: AgentFrameworkProperties
    ): A2AConfiguration {
        val a2aConfig = properties.features.a2a
        return A2AConfiguration(
            enabled = true,
            port = a2aConfig.port,
            host = a2aConfig.host
        )
    }
    
    // NEW: Property-based Observability configuration
    @Bean
    @Primary
    @ConditionalOnProperty("embabel.agent.features.observability.enabled", havingValue = "true")
    fun observabilityConfigurationFromProperties(
        properties: AgentFrameworkProperties
    ): ObservabilityConfiguration {
        val obsConfig = properties.features.observability
        return ObservabilityConfiguration(
            enabled = true,
            metricsEnabled = obsConfig.metricsEnabled,
            tracingEnabled = obsConfig.tracingEnabled
        )
    }
    
    // NOTE: NO shell configuration here
    // Shell configuration is handled entirely by embabel-agent-shell module
    // using embabel.shell.* properties prefix
    
    // Keep existing @Profile-based feature configurations UNCHANGED
    @Bean
    @Profile("a2a")
    @ConditionalOnMissingBean(A2AConfiguration::class)
    fun a2aFromProfile(): A2AConfiguration {
        return A2AConfiguration(enabled = true)
    }
    
    @Bean
    @Profile("observability")
    @ConditionalOnMissingBean(ObservabilityConfiguration::class)
    fun observabilityFromProfile(): ObservabilityConfiguration {
        return ObservabilityConfiguration(enabled = true)
    }
}
```

---

## Week 3: Testing & Validation (18 hours)

### Task 3A: Dual Configuration Testing (12 hours)

#### Deliverables:
- Comprehensive test suite
- Precedence validation
- Integration tests (core framework only)

#### Implementation:

**File: `src/test/kotlin/com/embabel/agent/config/DualConfigurationTest.kt`**
```kotlin
@SpringBootTest
class DualConfigurationTest {
    
    @Nested
    @DisplayName("Personality Configuration Tests")
    inner class PersonalityTests {
        
        @Test
        @TestPropertySource(properties = ["embabel.agent.logging.personality=starwars"])
        fun `property-based personality should take precedence over profile`() {
            // Test that @Primary property-based bean wins
        }
        
        @Test
        @ActiveProfiles("starwars")
        fun `profile-based personality should work when no property set`() {
            // Test fallback to @Profile beans
        }
        
        @Test
        @TestPropertySource(properties = ["embabel.agent.logging.personality=starwars"])
        @ActiveProfiles("severance")
        fun `property should override conflicting profile`() {
            // Test @Primary precedence
        }
        
        @Test
        fun `default personality should be used when neither property nor profile set`() {
            // Test @ConditionalOnMissingBean fallback
        }
    }
    
    @Nested
    @DisplayName("Model Provider Configuration Tests")
    inner class ModelProviderTests {
        
        @Test
        @TestPropertySource(properties = [
            "embabel.agent.models.provider=openai",
            "embabel.agent.models.openai.apiKey=test-key"
        ])
        fun `should create OpenAI provider from properties`() {
            // Test property-based model provider creation
        }
        
        @Test
        @ActiveProfiles("anthropic") 
        fun `should fallback to profile-based model provider`() {
            // Test profile fallback when properties not set
        }
    }
    
    @Nested
    @DisplayName("Test Profile Strategy Tests")
    inner class TestProfileTests {
        
        @Test
        @ActiveProfiles("test")
        @TestPropertySource(properties = ["embabel.agent.models.provider=openai"])
        fun `should use mock provider in test profile for safety`() {
            // Test that real provider request in test profile uses mock for safety
            val provider = applicationContext.getBean(ModelProvider::class.java)
            assertThat(provider).isInstanceOf(MockModelProvider::class.java)
        }
        
        @Test
        @ActiveProfiles("test")
        @TestPropertySource(properties = [
            "embabel.agent.models.provider=openai",
            "embabel.agent.models.test.allowRealProviders=true"
        ])
        fun `should allow real provider in test profile when explicitly enabled`() {
            // Test override for integration tests
            val provider = applicationContext.getBean(ModelProvider::class.java)
            assertThat(provider).isInstanceOf(OpenAiModelProvider::class.java)
        }
        
        @Test
        @ActiveProfiles("prod")
        @TestPropertySource(properties = ["embabel.agent.models.provider=openai"])
        fun `should use real provider in production profile`() {
            // Test normal production behavior
            val provider = applicationContext.getBean(ModelProvider::class.java)
            assertThat(provider).isInstanceOf(OpenAiModelProvider::class.java)
        }
        
        @Test
        @ActiveProfiles("test")
        fun `should use profile-based test provider when no property set`() {
            // Test @Profile("test") fallback
            val provider = applicationContext.getBean(ModelProvider::class.java)
            assertThat(provider).isInstanceOf(MockModelProvider::class.java)
        }
        
        @Test
        @ActiveProfiles("prod")
        fun `should NOT create test beans in production profile`() {
            // Test @Profile("test") exclusion
            assertThat(applicationContext.getBeanNamesForType(ModelProvider::class.java))
                .noneMatch { it.contains("test") }
        }
        
        @Test
        @ActiveProfiles("test")
        fun `should NOT create production beans in test profile`() {
            // Test @Profile("!test") exclusion
            assertThat(applicationContext.getBeanNamesForType(ModelProvider::class.java))
                .noneMatch { it.contains("production") }
        }
    }

    @Nested
    @DisplayName("Feature Configuration Tests - Core Framework Only")
    inner class FeatureTests {
        
        @Test
        @TestPropertySource(properties = ["embabel.agent.features.a2a.enabled=true"])
        fun `should enable A2A via properties`() {
            // Test feature toggle via properties
        }
        
        @Test
        @ActiveProfiles("a2a")
        fun `should enable A2A via profile when property not set`() {
            // Test profile fallback for features
        }
        
        @Test
        @TestPropertySource(properties = ["embabel.agent.features.observability.enabled=true"])
        fun `should enable observability via properties`() {
            // Test observability feature toggle
        }
        
        // NOTE: NO shell tests here - shell module handles its own configuration testing
    }
    
    @Nested
    @DisplayName("Shell Module Independence Tests")
    inner class ShellIndependenceTests {
        
        @Test
        fun `core framework should work without shell module on classpath`() {
            // Test that core framework doesn't require shell module
        }
        
        @Test
        @TestPropertySource(properties = [
            "embabel.agent.logging.personality=starwars",
            "embabel.shell.enabled=true"  // Different namespace
        ])
        fun `should not conflict with shell module properties`() {
            // Test namespace separation
        }
    }
}
```

**File: `src/test/kotlin/com/embabel/agent/config/ConfigurationPrecedenceTest.kt`**
```kotlin
@SpringBootTest
class ConfigurationPrecedenceTest {
    
    /**
     * Test matrix for configuration precedence validation (core framework only)
     */
    @ParameterizedTest
    @CsvSource(
        "starwars,     ,        starwars,   PROPERTY",
        "        , starwars,    starwars,   PROFILE", 
        "starwars, severance,   starwars,   PROPERTY",
        "        ,         ,    default,    DEFAULT"
    )
    fun `should respect configuration precedence for core framework`(
        property: String?,
        profile: String?, 
        expectedPersonality: String,
        expectedSource: ConfigurationSource
    ) {
        // Test all precedence combinations for core framework
        // NO shell-related tests
    }
    
    /**
     * Test matrix for test/production profile precedence
     */
    @ParameterizedTest
    @CsvSource(
        "mock,      test,    MockModelProvider,        PROPERTY_TEST",
        "openai,    test,    MockModelProvider,        SAFETY_OVERRIDE",
        "openai,    prod,    OpenAiModelProvider,      PROPERTY_PROD", 
        "        ,  test,    MockModelProvider,        PROFILE_TEST",
        "        ,  prod,    OpenAiModelProvider,      PROFILE_PROD"
    )
    fun `should respect test vs production profile precedence`(
        propertyProvider: String?,
        activeProfile: String,
        expectedProviderType: String,
        expectedSource: TestConfigurationSource
    ) {
        // Test @Profile("test") vs @Profile("!test") behavior
    }
    
    enum class ConfigurationSource { PROPERTY, PROFILE, DEFAULT }
    enum class TestConfigurationSource { 
        PROPERTY_TEST, PROPERTY_PROD, SAFETY_OVERRIDE, PROFILE_TEST, PROFILE_PROD 
    }
}
```

### Task 3B: Backward Compatibility Testing (6 hours)

#### Deliverables:
- Regression test suite
- Performance validation
- Existing application compatibility (core framework)

#### Implementation:

**File: `src/test/kotlin/com/embabel/agent/config/BackwardCompatibilityTest.kt`**
```kotlin
@SpringBootTest
class BackwardCompatibilityTest {
    
    @Test
    @ActiveProfiles("starwars")
    fun `existing starwars profile configuration should work unchanged`() {
        // Verify existing apps using profiles continue to work
    }
    
    @Test
    @ActiveProfiles("severance", "a2a")
    fun `multiple profile configuration should work unchanged`() {
        // Verify complex profile combinations still work
        // NOTE: Testing a2a but NOT shell profiles
    }
    
    @Test
    fun `existing default configuration should work unchanged`() {
        // Verify apps with no profiles/properties get defaults
    }
    
    @Test
    fun `performance should not degrade with dual configuration`() {
        // Benchmark configuration loading times
    }
    
    @Test
    fun `should not break when shell module is absent`() {
        // Verify core framework independence from shell module
    }
}
```

---

## Week 4: Documentation & Examples (18 hours)

### Task 4A: Configuration Documentation (10 hours)

#### Deliverables:
- Comprehensive configuration guide
- Migration examples
- Best practices documentation
- Clear shell module separation documentation

#### Implementation:

**File: `docs/CONFIGURATION.md`**
```markdown
# Embabel Agent Framework Configuration

## Overview

The Embabel Agent Framework supports two configuration approaches:

1. **Property-based Configuration** (Recommended for new applications)
2. **Profile-based Configuration** (Legacy, fully supported)

When both are present, property-based configuration takes precedence.

## Module Configuration Separation

### Core Framework Configuration (`embabel.agent.*`)
The core framework (`embabel-agent-api`) handles:
- Logging personalities and themes
- AI model providers  
- Agent-to-Agent (A2A) communication
- Observability features
- Autonomy settings

### Shell Module Configuration (`embabel.shell.*`)
The shell module (`embabel-agent-shell`) handles:
- Shell interface enablement
- Terminal settings
- Shell commands configuration
- Chat session settings

**Important:** These are completely separate configuration namespaces with no cross-dependencies.

## Core Framework Property-Based Configuration

### Complete Example

```yaml
embabel:
  agent:
    logging:
      personality: starwars
      verbosity: debug
      colorPalette: starwars
    models:
      provider: openai
      openai:
        apiKey: ${OPENAI_API_KEY}
        model: gpt-4
        organization: org-123
    features:
      a2a:
        enabled: true
        port: 8081
        host: localhost
      observability:
        enabled: true
        metricsEnabled: true
        tracingEnabled: true
    autonomy:
      goalConfidenceCutOff: 0.8
      agentConfidenceCutOff: 0.75
      maxRetries: 5
```

### Shell Module Configuration (Separate)

```yaml
embabel:
  shell:
    enabled: true
    lineLength: 140
    chat:
      confirmGoals: true
      bindConversation: false
```

### Logging Configuration

| Property | Values | Default | Description |
|----------|--------|---------|-------------|
| `personality` | `starwars`, `severance`, `hitchhiker`, `colossus`, `montypython` | `default` | Logging theme |
| `verbosity` | `quiet`, `normal`, `verbose`, `debug` | `normal` | Log detail level |
| `colorPalette` | Same as personality values | Auto-selected | Custom color scheme |

### Model Configuration

| Property | Values | Default | Description |
|----------|--------|---------|-------------|
| `provider` | `openai`, `anthropic`, `bedrock`, `ollama` | `openai` | AI model provider |

#### OpenAI Configuration
```yaml
embabel:
  agent:
    models:
      provider: openai
      openai:
        apiKey: ${OPENAI_API_KEY}
        model: gpt-4
        organization: org-123
        baseUrl: https://api.openai.com/v1  # Optional
```

#### Anthropic Configuration
```yaml
embabel:
  agent:
    models:
      provider: anthropic
      anthropic:
        apiKey: ${ANTHROPIC_API_KEY}
        model: claude-3-sonnet-20240229
        maxTokens: 4096
```

## Profile-Based Configuration (Legacy)

Still fully supported for backward compatibility:

```yaml
spring:
  profiles:
    active: starwars,a2a,observability
    # NOTE: Shell profiles are handled by shell module independently
```

## Configuration Precedence

1. **Properties** (highest priority) - `embabel.agent.*`
2. **Profiles** (fallback) - `spring.profiles.active`
3. **Defaults** (lowest priority) - built-in defaults

## Module Independence

### Core Framework Without Shell
```yaml
# Core framework only - no shell dependencies
embabel:
  agent:
    logging:
      personality: starwars
    models:
      provider: openai
```

### With Shell Module (Optional)
```yaml
# Core framework configuration
embabel:
  agent:
    logging:
      personality: starwars
    models:
      provider: openai

# Shell module configuration (separate namespace)
embabel:
  shell:
    enabled: true
    lineLength: 140
```

## Migration Guide

### From Profiles to Properties

**Before:**
```yaml
spring:
  profiles:
    active: starwars,a2a
```

**After:**
```yaml
embabel:
  agent:
    logging:
      personality: starwars
    features:
      a2a:
        enabled: true
```

**Shell Module (if used):**
```yaml
embabel:
  shell:
    enabled: true
```

### Benefits of Property-Based Configuration

- **Explicit configuration** - clear what features are enabled
- **Type safety** - validated property binding
- **IDE support** - auto-completion and validation
- **Environment flexibility** - easy override with env vars
- **Module independence** - core and shell configured separately
- **Documentation** - self-documenting configuration structure
```

### Task 4B: Migration Examples (8 hours)

#### Deliverables:
- Before/after migration examples
- Integration patterns
- Troubleshooting guide
- Shell module integration examples

**File: `docs/MIGRATION_EXAMPLES.md`**
```markdown
# Migration Examples

## Basic Application Migration

### Before (Profile-based)
```kotlin
@SpringBootApplication
@ComponentScan("com.mycompany.agents")
class MyApplication

fun main(args: Array<String>) {
    System.setProperty("spring.profiles.active", "starwars,a2a")
    runApplication<MyApplication>(*args)
}
```

### After (Property-based)
```kotlin
@SpringBootApplication
@ComponentScan("com.mycompany.agents")
class MyApplication

// No need to set profiles programmatically
fun main(args: Array<String>) {
    runApplication<MyApplication>(*args)
}
```

```yaml
# application.yml
embabel:
  agent:
    logging:
      personality: starwars
    features:
      a2a:
        enabled: true
```

## Complex Configuration Migration with Shell Module

### Before
```yaml
spring:
  profiles:
    active: severance,a2a,observability,shell

# Profile-specific files needed:
# application-severance.yml
# application-a2a.yml  
# application-observability.yml
# application-shell.yml
```

### After
```yaml
# Core framework configuration
embabel:
  agent:
    logging:
      personality: severance
      verbosity: debug
    models:
      provider: anthropic
      anthropic:
        apiKey: ${ANTHROPIC_API_KEY}
    features:
      a2a:
        enabled: true
        port: 8082
      observability:
        enabled: true
        metricsEnabled: true
        tracingEnabled: true
    autonomy:
      goalConfidenceCutOff: 0.85

# Shell module configuration (separate namespace)
embabel:
  shell:
    enabled: true
    lineLength: 160
    chat:
      confirmGoals: true
      bindConversation: false
```

## Module Independence Examples

### Core Framework Only (No Shell)
```xml
<dependencies>
    <dependency>
        <groupId>com.embabel</groupId>
        <artifactId>embabel-agent-api</artifactId>
    </dependency>
    <!-- NO shell dependency -->
</dependencies>
```

```yaml
embabel:
  agent:
    logging:
      personality: starwars
    models:
      provider: openai
    # No shell configuration needed
```

### Core Framework + Shell Module
```xml
<dependencies>
    <dependency>
        <groupId>com.embabel</groupId>
        <artifactId>embabel-agent-api</artifactId>
    </dependency>
    <dependency>
        <groupId>com.embabel</groupId>
        <artifactId>embabel-agent-shell</artifactId>
    </dependency>
</dependencies>
```

```yaml
# Core framework
embabel:
  agent:
    logging:
      personality: starwars

# Shell module (independent configuration)
embabel:
  shell:
    enabled: true
    lineLength: 140
```

## Environment-Specific Configuration

### Using Environment Variables
```bash
# Core framework
export EMBABEL_AGENT_LOGGING_PERSONALITY=starwars
export EMBABEL_AGENT_MODELS_PROVIDER=anthropic
export EMBABEL_AGENT_FEATURES_A2A_ENABLED=true

# Shell module (separate namespace)
export EMBABEL_SHELL_ENABLED=true
export EMBABEL_SHELL_LINELENGTH=160
```

## Troubleshooting

### Common Issues

1. **Both profiles and properties set**
   - Properties take precedence
   - Check logs for "Creating ... from properties" vs "Creating ... from profile"

2. **Invalid property values**
   - Check supported values in documentation
   - Application will log warnings and use defaults

3. **Missing API keys**
   - Use environment variables: `${OPENAI_API_KEY}`
   - Check startup logs for configuration validation

4. **Shell module not working**
   - Verify `embabel.shell.enabled=true`
   - Check that embabel-agent-shell dependency is included
   - Shell uses separate configuration namespace

5. **Configuration namespace confusion**
   - Core framework: `embabel.agent.*`
   - Shell module: `embabel.shell.*`
   - These are completely independent
```

---

## Week 5: Polish & Preparation for Phase 2 (23 hours)

### Task 5A: Edge Cases & Refinement (10 hours)

#### Deliverables:
- Property validation enhancements
- Error handling improvements
- Performance optimizations
- Shell module independence validation

#### Implementation:

**File: `src/main/kotlin/com/embabel/agent/config/PropertyValidation.kt`**
```kotlin
@Component
@ConfigurationPropertiesBinding
class AgentFrameworkPropertiesValidator : Validator {
    
    override fun supports(clazz: Class<*>): Boolean = 
        AgentFrameworkProperties::class.java.isAssignableFrom(clazz)
    
    override fun validate(target: Any, errors: Errors) {
        val properties = target as AgentFrameworkProperties
        
        // Validate personality
        properties.logging.personality?.let { personality ->
            if (!isValidPersonality(personality)) {
                errors.rejectValue("logging.personality", 
                    "invalid.personality", 
                    "Invalid personality: $personality. Valid values: ${validPersonalities()}")
            }
        }
        
        // Validate confidence cutoffs
        validateConfidenceCutOff(properties.autonomy.goalConfidenceCutOff, "autonomy.goalConfidenceCutOff", errors)
        validateConfidenceCutOff(properties.autonomy.agentConfidenceCutOff, "autonomy.agentConfidenceCutOff", errors)
        
        // Validate model provider
        properties.models.provider?.let { provider ->
            if (!isValidModelProvider(provider)) {
                errors.rejectValue("models.provider",
                    "invalid.provider",
                    "Invalid model provider: $provider. Valid values: ${validModelProviders()}")
            }
        }
        
        // Validate A2A configuration
        if (properties.features.a2a.enabled) {
            validatePort(properties.features.a2a.port, "features.a2a.port", errors)
            validateHost(properties.features.a2a.host, "features.a2a.host", errors)
        }
        
        // NOTE: NO shell validation here - shell module handles its own validation
    }
    
    private fun isValidPersonality(personality: String): Boolean =
        personality.lowercase() in setOf("starwars", "severance", "hitchhiker", "colossus", "montypython", "default")
    
    private fun validPersonalities(): String = 
        "starwars, severance, hitchhiker, colossus, montypython, default"
    
    private fun validateConfidenceCutOff(value: Double, field: String, errors: Errors) {
        if (value < 0.0 || value > 1.0) {
            errors.rejectValue(field, "invalid.range", "Confidence cutoff must be between 0.0 and 1.0")
        }
    }
    
    private fun isValidModelProvider(provider: String): Boolean =
        provider.lowercase() in setOf("openai", "anthropic", "bedrock", "ollama")
    
    private fun validModelProviders(): String =
        "openai, anthropic, bedrock, ollama"
    
    private fun validatePort(port: Int, field: String, errors: Errors) {
        if (port < 1024 || port > 65535) {
            errors.rejectValue(field, "invalid.port", "Port must be between 1024 and 65535")
        }
    }
    
    private fun validateHost(host: String, field: String, errors: Errors) {
        if (host.isBlank()) {
            errors.rejectValue(field, "invalid.host", "Host cannot be blank")
        }
    }
}
```

**File: `src/test/kotlin/com/embabel/agent/config/ShellModuleIndependenceTest.kt`**
```kotlin
@SpringBootTest
class ShellModuleIndependenceTest {
    
    @Test
    fun `core framework should work without shell module dependency`() {
        // Test that core framework functions completely without shell on classpath
    }
    
    @Test
    @TestPropertySource(properties = [
        "embabel.agent.logging.personality=starwars",
        "embabel.shell.enabled=true"  // Different namespace - should not interfere
    ])
    fun `should handle separate configuration namespaces correctly`() {
        // Test that embabel.agent.* and embabel.shell.* don't interfere
    }
    
    @Test
    fun `should not create shell-related beans in core framework`() {
        // Verify no shell beans are created by core framework configuration
    }
    
    @Test  
    fun `configuration properties should not reference shell configuration`() {
        val properties = AgentFrameworkProperties()
        val features = properties.features
        
        // Verify no shell properties exist in core framework configuration
        assertThat(features).doesNotHaveShellConfiguration()
    }
}
```

### Task 5B: Configuration Precedence & Integration Strategy (5 hours)

#### Deliverables:
- **Configuration Override Precedence** implementation following Spring Boot best practices
- **Existing YAML Integration** - application.yaml, application-docker.yaml, application-neo.yaml work as-is
- **Optional Import Pattern** documentation for new application YAML files
- **Migration guidelines** for seamless adoption

#### Configuration Override Precedence (Highest to Lowest Priority):

1. **Programmatic Properties** (Highest Priority)
   - `@TestPropertySource(properties = [...])`
   - System properties (`-Dembabel.agent.logging.personality=starwars`)
   - Environment variables (`EMBABEL_AGENT_LOGGING_PERSONALITY=starwars`)

2. **Properties Files** 
   - `application.properties`
   - `application-{profile}.properties`

3. **YAML Files**
   - `application.yaml` (existing - works as-is with property semantics)
   - `application-docker.yaml` (existing - works as-is)
   - `application-neo.yaml` (existing - works as-is)
   - `application-{profile}.yaml`

4. **Programmatic Bean Configuration** (Lowest Priority)
   - `@Bean` methods with `@ConditionalOnMissingBean`
   - Default values in `@ConfigurationProperties`

#### Implementation Strategy:

**File: `src/main/kotlin/com/embabel/agent/config/AgentFrameworkConfiguration.kt`**
```kotlin
@Configuration
@EnableConfigurationProperties(AgentFrameworkProperties::class)
class AgentFrameworkConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty("embabel.agent.logging.personality")
    fun personalityProviderFromProperties(
        properties: AgentFrameworkProperties
    ): PersonalityProvider {
        // Highest precedence: Property-based configuration
        return createPersonalityProvider(properties.logging.personality!!)
    }
    
    @Bean
    @Profile("starwars")
    @ConditionalOnMissingBean(PersonalityProvider::class)
    fun starwarsPersonalityFromProfile(): PersonalityProvider {
        // Medium precedence: Profile-based configuration (existing YAML support)
        return StarWarsPersonalityProvider()
    }
    
    @Bean
    @ConditionalOnMissingBean(PersonalityProvider::class)
    fun defaultPersonalityProvider(): PersonalityProvider {
        // Lowest precedence: Programmatic default
        return DefaultPersonalityProvider()
    }
}
```

#### Existing YAML Integration:

**Existing Files Work As-Is:**
```yaml
# application-docker.yaml (NO CHANGES NEEDED)
spring:
  profiles:
    active: docker

# Existing configuration continues to work with property semantics
embabel:
  agent:
    logging:
      personality: starwars
    models:
      provider: openai
```

**Optional Import Pattern for New YAML Files:**
```yaml
# application-myapp.yaml (NEW - created by application developers)
spring:
  config:
    import: 
      - optional:classpath:embabel-agent-starwars.properties
      - optional:file:.env[.properties]

# Application-specific configuration
server:
  port: 8080
```

#### Migration Strategy:

**Phase 1 Approach:**
- ✅ **Existing YAML files** continue working unchanged
- ✅ **Properties files** can override YAML configuration  
- ✅ **Programmatic properties** have highest precedence
- ✅ **@Primary property-based beans** override profile-based beans
- ✅ **Backward compatibility** maintained

### Task 5C: Phase 2 Preparation (8 hours)

#### Deliverables:
- Phase 2 planning document
- Auto-config analysis
- Deprecation strategy
- Shell module coordination planning

**File: `docs/PHASE_2_PREPARATION.md`**
```markdown
# Phase 2 Preparation

## Current State Analysis

### Profile Usage in Core Framework
- `embabel-agent-api` now supports dual configuration (properties + profiles)
- Approximately 10-12 configuration classes with @Profile annotations
- Shell module completely independent with its own configuration namespace

### Profile Usage in Auto-Configuration
- `embabel-agent-autoconfigure` module heavily uses `@Profile` annotations
- Approximately 15-20 configuration classes depend on profiles
- Auto-configuration provides enhanced property support on top of basic framework

### Shell Module Independence Achieved ✅
- Shell configuration completely encapsulated in `embabel-agent-shell`
- No cross-dependencies between core framework and shell
- Separate configuration namespaces: `embabel.agent.*` vs `embabel.shell.*`

## Phase 2 Goals

### 1. Enhanced Auto-Configuration
- Migrate auto-config from profiles to properties
- Add advanced property validation
- Implement conditional auto-configuration
- Maintain shell module independence

### 2. Profile Deprecation Strategy
- Add deprecation warnings to all @Profile beans
- Provide migration utilities
- Clear timeline for profile removal
- Coordinate with shell module deprecation (if any)

### 3. Spring Boot Starter Creation
- Zero-configuration experience
- Sensible defaults
- Easy customization
- Include shell module optionally

## Module Configuration Architecture (Phase 2)

### Core Framework Auto-Configuration
```yaml
embabel:
  agent:
    # Enhanced properties with validation
    logging:
      personality: starwars
      verbosity: debug
    models:
      provider: openai
      validation:
        enabled: true
    features:
      a2a:
        enabled: true
        security:
          enabled: true
```

### Shell Module Auto-Configuration (Independent)
```yaml
embabel:
  shell:
    # Shell module handles its own auto-configuration
    enabled: true
    advanced:
      historySize: 1000
      completion: true
```

## Recommended Phase 2 Timeline

### Week 1-2: Core Framework Auto-Configuration Enhancement
- Create property-based auto-configuration classes
- Add @Primary beans for auto-config properties
- Maintain backward compatibility
- NO shell auto-config (handled by shell module)

### Week 3-4: Deprecation Implementation  
- Add deprecation warnings to framework profiles
- Add deprecation warnings to auto-config profiles
- Create migration utilities
- Coordinate with shell module deprecation timeline

### Week 5-6: Spring Boot Starter
- Create embabel-spring-boot-starter module
- Add starter auto-configuration
- Optional shell module inclusion
- Create starter documentation

## Shell Module Coordination

### Phase 2 Shell Module Tasks (Parallel to Core Framework)
- Shell module can independently implement its own auto-configuration
- Shell module can add its own deprecation warnings for shell profiles
- Shell module integration with starter (optional dependency)

### No Cross-Dependencies
- Core framework Phase 2 doesn't require shell module changes
- Shell module can evolve independently
- Starter can optionally include shell module

## Breaking Changes Schedule

### Phase 2 (Non-Breaking)
- Deprecation warnings added
- Property-based configuration enhanced
- Migration tools provided
- Shell module remains independent

### Phase 3 (Breaking - Future)
- Remove all @Profile annotations from core framework
- Remove all @Profile annotations from auto-configuration
- Shell module decides its own deprecation timeline
- Property-based configuration only

## Migration Strategy

### Core Framework Level (Phase 1 ✅)
- [x] Property support added alongside profiles
- [x] @Primary ensures property precedence
- [x] No breaking changes
- [x] Shell module completely independent

### Auto-Configuration Level (Phase 2)
- [ ] Enhanced property support in auto-config
- [ ] Deprecation warnings for profile usage
- [ ] Migration documentation
- [ ] Shell module auto-config (independent)

### Complete Migration (Phase 3)
- [ ] Remove profile support entirely from core framework
- [ ] Remove profile support entirely from auto-configuration
- [ ] Shell module decides its own migration timeline
- [ ] Property-only configuration
- [ ] Clean, library-focused architecture

## Shell Module Independence Benefits

### Achieved in Phase 1 ✅
- Complete configuration separation
- No cross-module dependencies
- Independent evolution paths
- Clear module boundaries

### Phase 2 Benefits
- Core framework auto-config can proceed without shell concerns
- Shell module can implement its own auto-configuration independently
- Starter can optionally include shell module
- Simplified dependency management

## Risk Management Updates

### Risks Eliminated by Shell Independence ✅
- No shell-related breaking changes in core framework
- No configuration namespace conflicts
- No cross-module migration coordination required

### Remaining Low Risks
- Auto-configuration property migration
- Starter module dependency management
- Documentation coordination between modules

### High Value, Low Risk
- Shell module can be excluded cleanly from any deployment
- Core framework auto-configuration is simplified
- Clear module boundaries reduce complexity
```

---

## Testing Strategy

### Unit Tests
- Property binding validation (core framework only)
- Configuration precedence
- Bean creation logic
- Error handling
- Shell module independence validation

### Integration Tests  
- End-to-end configuration scenarios (core framework)
- Profile/property combinations
- Backward compatibility
- Performance benchmarks
- Module independence verification

### Cross-Module Tests
- Verify core framework works without shell module
- Test separate configuration namespaces
- Validate no shell dependencies in core

### Test Coverage Goals
- **90%+ coverage** for new configuration classes
- **100% coverage** for property validation logic
- **Comprehensive integration testing** for all configuration combinations
- **Shell independence verification** in all test scenarios

---

## Risk Management

### Low Risk Items ✅
- Adding @EnableConfigurationProperties to existing configuration
- Creating new property classes with defaults
- Adding @Primary beans alongside existing @Profile beans
- Shell module independence (already achieved)

### Medium Risk Items
- Modifying existing configuration classes
- Complex conditional logic for dual support
- Property validation implementation

### High Risk Items
- None in Phase 1 (conservative approach with shell independence)

### Mitigation Strategies
- Comprehensive backward compatibility testing
- Feature flags for new functionality
- Gradual rollout with monitoring
- Clear rollback procedures
- Shell module can be excluded if issues arise

---

## Success Metrics

### Functional Metrics
- ✅ All existing profile-based applications work unchanged
- ✅ Property-based configuration works correctly
- ✅ @Primary precedence functions as expected
- ✅ Performance maintained or improved
- ✅ Shell module completely independent

### Quality Metrics
- ✅ 90%+ test coverage for new code
- ✅ Zero breaking changes introduced
- ✅ Documentation completeness score 95%+
- ✅ All integration tests passing
- ✅ Shell independence verified

### Developer Experience Metrics
- ✅ Clear migration path documented
- ✅ Configuration examples provided
- ✅ IDE auto-completion works for properties
- ✅ Validation errors are clear and actionable
- ✅ Module boundaries are clear and logical

---

## Deliverables Summary

### Code Artifacts
1. `AgentFrameworkProperties.kt` - Complete property structure (NO shell references)
2. Modified configuration classes with dual support
3. Comprehensive test suite (60+ tests)
4. Property validation framework
5. Shell independence validation tests

### Documentation Artifacts
1. `CONFIGURATION.md` - Complete configuration guide with module separation
2. `MIGRATION_EXAMPLES.md` - Practical migration examples including shell independence
3. `PHASE_2_PREPARATION.md` - Next phase planning with shell coordination
4. API documentation updates emphasizing module boundaries

### Process Artifacts
1. Test coverage reports
2. Performance benchmarks
3. Backward compatibility validation
4. Shell module independence verification
5. Phase 2 implementation plan

### Outstanding Issues (Phase 1)
1. **TODO: Revisit @Profile("!test") Pattern**
   - Current approach uses `@Profile("!test")` for non-test environments
   - Goal: Replace with `@ConditionalOnMissingBean` for cleaner library design
   - Challenge: `@ConditionalOnMissingBean` alone would apply to test environments too
   - Options to explore:
     - Custom conditional annotation `@ConditionalOnNotTestProfile`
     - Property-based conditionals
     - Environment-based expressions
     - Keep `@Profile("!test")` as pragmatic solution
   - Timeline: Address in Phase 1 implementation or defer to Phase 2

2. **Model Provider Configuration Evolution Strategy**
   
   **Current State**: Individual model classes (`BedrockModels.kt`, `OllamaModels.kt`) with profile-based activation
   ```kotlin
   // Current - profile-based activation
   @Configuration
   class BedrockModels {
       @PostConstruct
       fun registerModels() {
           if (!environment.activeProfiles.contains(BEDROCK_PROFILE)) return  // OLD WAY
           // Register bedrock models
       }
   }
   ```
   
   **Phase 1 Changes**: Replace profile checks with property-based activation
   ```kotlin
   // Phase 1 - property-based activation (immediate change)
   @Configuration
   @ConditionalOnProperty("embabel.agent.models.bedrock.enabled", havingValue = "true", matchIfMissing = true)
   class BedrockModels {
       @PostConstruct
       fun registerModels() {
           // No profile check needed - Spring handles activation
           // Register bedrock models (existing logic unchanged)
       }
   }
   ```
   
   **Target Architecture**: Hybrid Property-Based + Auto-Discovery
   ```kotlin
   // Phase 3 - Full auto-discovery with property override
   @Configuration
   @ConditionalOnProperty("embabel.agent.models.bedrock.enabled", havingValue = "true", matchIfMissing = true)
   @ConditionalOnClass(BedrockRuntimeClient::class)  // Auto-discover if SDK present
   class BedrockModels {
       @PostConstruct
       fun registerModels() {
           // Auto-register if AWS SDK is on classpath
       }
   }
   ```
   
   **Implementation Timeline:**
   
   **Phase 1 (Conservative - Minimum Changes):**
   - **Focus**: Core framework properties only (`AgentFrameworkProperties`)
   - **Model Providers**: Replace profile checks with `@ConditionalOnProperty` (low risk)
   - **Profile Migration**: Start moving away from profiles immediately
   - **No Breaking Changes**: Existing applications continue working
   
   **Phase 2 (Enhanced Property-Based Configuration):**
   - Add comprehensive model provider properties to `AgentFrameworkProperties`
   - Enhanced property validation and configuration
   - Unified model provider configuration interface
   - Maintain backward compatibility
   
   **Phase 3 (Auto-Discovery Enhancement):**
   - Add auto-discovery based on classpath detection
   - Implement hybrid approach with precedence:
     1. Property-based (explicit)
     2. Auto-discovery (fallback)
   - Full declarative plugin system
   
   **Phase 1 Minimum Required Changes:**
   ```kotlin
   // AgentFrameworkProperties - framework internals only
   @ConfigurationProperties(prefix = "embabel.framework")
   data class AgentFrameworkProperties(
       @NestedConfigurationProperty
       val logging: LoggingConfig = LoggingConfig(),
       
       @NestedConfigurationProperty
       val scanning: ScanningConfig = ScanningConfig(),
       
       @NestedConfigurationProperty
       val test: TestConfig = TestConfig()
       
       // NO models property - deferred to Phase 2
       // NO autonomy property - application concern
   )
   ```
   
   **Key Phase 1 Decisions:**
   - Model provider classes remain in core framework (not autoconfigure)
   - Profile-based activation preserved temporarily
   - Focus on true framework internals only
   - Model provider evolution documented for future phases

---

## Conclusion

Phase 1 establishes a solid foundation for library-centric configuration while maintaining full backward compatibility and achieving complete shell module independence. The dual configuration approach allows for gradual migration from profiles to properties, while the clear module separation ensures that shell functionality is completely encapsulated within the shell module.

The conservative approach minimizes risk while achieving the core goals of:
1. Making embabel-agent-api suitable for library consumption
2. Modern, type-safe configuration management  
3. Complete shell module independence
4. Clear module boundaries and responsibilities

Phase 2 can now proceed with enhanced auto-configuration for the core framework without any shell module concerns, as shell configuration is completely encapsulated within its own module with its own configuration namespace.