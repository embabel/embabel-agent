/*
 * Copyright 2024-2026 Embabel Pty Ltd.
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
package com.embabel.common.ai.model

import com.embabel.common.ai.classification.ClassificationService
import com.embabel.common.ai.classification.ClassificationServiceMetadata
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionService
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory

/**
 * Registered decision and classification services with their family defaults and role bindings.
 *
 * The registry is an immutable snapshot taken when [Builder.build] runs. In Spring that happens when
 * the registry bean is created. A service created after that point is not visible to lookups; pass
 * it to [ServiceSelector.using] instead. Instances are safe to share between threads.
 *
 * Each service registers under a registration name. The decision family holds the registered
 * [DecisionService] instances. The classification family holds every registered service, because a
 * decision service also classifies. Roles and defaults are scoped to one family.
 *
 * A family default is resolved in this order: the explicit default, then the single
 * [DefaultCandidate] eligible for the family, then the single service eligible for the family.
 * Otherwise [ServiceSelector.defaultService] throws [ServiceSelectionException].
 * A name no registered service holds is resolved through the [DecisionModelProvider]s, in the order
 * they were added. Each provider model is created once, on first lookup, and serves both families.
 * Defaults and roles may name a provider model. Provider models are never chosen as a default by the
 * candidate or single-service rules.
 *
 * Selection is done by a [ModelRegistry] with one family per capability interface. This class
 * keeps the decision-specific parts: the two families, the capabilities summary and the
 * observation registry.
 */
@ApiStatus.Experimental
class DecisionServiceRegistry private constructor(
    private val registry: ModelRegistry<ClassificationService>,
    private val snapshots: List<ClassificationServiceMetadata>,
    private val candidates: List<String>,
    private val capabilities: Map<String, DecisionCapabilities>,
    observationRegistry: ObservationRegistry,
) {

    /**
     * The observation registry the registered services observe with. Operation bindings open
     * observation scopes on it.
     */
    @get:ApiStatus.Internal
    val observationRegistry: ObservationRegistry = observationRegistry

    private val decisionSelector: ServiceSelector<DecisionService> = registry.select(DecisionService::class.java)
    private val classificationSelector: ServiceSelector<ClassificationService> =
        registry.select(ClassificationService::class.java)

    /**
     * Returns the selector for the decision family.
     *
     * @return a selector over the registered decision services
     */
    fun decisions(): ServiceSelector<DecisionService> = decisionSelector

    /**
     * Returns the selector for the classification family, which includes decision services.
     *
     * @return a selector over every registered service
     */
    fun classifications(): ServiceSelector<ClassificationService> = classificationSelector

    /**
     * Returns the registration names in registration order.
     *
     * @return an unmodifiable list of names
     */
    fun registrationNames(): List<String> = java.util.List.copyOf(registry.registeredModels().keys)

    /**
     * Returns one metadata snapshot per registered service, in registration order. Each snapshot keeps
     * the service's family. It serves listings such as an admin view, and reads no service.
     *
     * @return an unmodifiable list of metadata snapshots
     */
    fun listServices(): List<ClassificationServiceMetadata> = snapshots

    /**
     * Names a registered service that provider configuration offers as a family default. A candidate
     * applies to a family only when no explicit default is set and it is the single candidate
     * eligible for that family.
     *
     * @param name the registration name of the candidate
     */
    @ApiStatus.Experimental
    class DefaultCandidate(val name: String) {
        init {
            require(name.isNotBlank()) { "default candidate name must not be blank" }
        }

        override fun equals(other: Any?): Boolean = other is DefaultCandidate && other.name == name

        override fun hashCode(): Int = name.hashCode()

        override fun toString(): String = "DefaultCandidate(name=$name)"
    }

    /**
     * Collects registrations, defaults, roles and candidates, and validates them in [build].
     * Instances are not thread-safe.
     */
    @ApiStatus.Experimental
    class Builder internal constructor() {
        private val models = ModelRegistry.builder<ClassificationService>()
            .family(DecisionService::class.java, DECISION_FAMILY)
            .family(ClassificationService::class.java, CLASSIFICATION_FAMILY)
            .describeWith { it.type.toString() }
        private val candidates = mutableListOf<String>()
        private var observationRegistry: ObservationRegistry = ObservationRegistry.NOOP

        /**
         * Registers [service] under [name].
         *
         * @param name the registration name, unique in the registry
         * @param service the service to register
         * @return this builder
         */
        fun register(name: String, service: ClassificationService): Builder = apply { models.register(name, service) }

        /**
         * Sets the decision family default.
         *
         * @param name the registration name of a decision service
         * @return this builder
         */
        fun decisionDefault(name: String): Builder = apply { models.defaultFor(DecisionService::class.java, name) }

        /**
         * Sets the classification family default.
         *
         * @param name the registration name of any registered service
         * @return this builder
         */
        fun classificationDefault(name: String): Builder = apply {
            models.defaultFor(ClassificationService::class.java, name)
        }

        /**
         * Binds [role] in the decision family to the service registered under [name].
         *
         * @param role the role name
         * @param name the registration name of a decision service
         * @return this builder
         */
        fun decisionRole(role: String, name: String): Builder = apply {
            models.role(DecisionService::class.java, role, name)
        }

        /**
         * Binds [role] in the classification family to the service registered under [name].
         *
         * @param role the role name
         * @param name the registration name of any registered service
         * @return this builder
         */
        fun classificationRole(role: String, name: String): Builder = apply {
            models.role(ClassificationService::class.java, role, name)
        }

        /**
         * Offers the service registered under [name] as a family default candidate.
         *
         * @param name the registration name of the candidate
         * @return this builder
         */
        fun defaultCandidate(name: String): Builder = apply {
            models.defaultCandidate(name)
            candidates += name
        }

        /**
         * Adds a provider for model names no registered service holds. Providers are asked in the
         * order they are added.
         *
         * @param provider the provider to add
         * @return this builder
         */
        fun provider(provider: DecisionModelProvider): Builder = apply { models.source(DecisionModelSource(provider)) }

        /**
         * Sets the observation registry. It must be the registry the registered services observe
         * with. With any other registry, operation bindings open scopes on the wrong registry and
         * provider calls lose their parent observation.
         *
         * @param registry the observation registry of the registered services
         * @return this builder
         */
        fun observationRegistry(registry: ObservationRegistry): Builder = apply { observationRegistry = registry }

        /**
         * Validates the bindings, builds the registry and logs one INFO summary. The capabilities of
         * each decision service are read once here for the summary.
         *
         * @return the immutable registry
         * @throws IllegalArgumentException when a name is blank or repeated, an instance is registered
         * twice, or a default, role or candidate names a missing or ineligible service
         * @throws IllegalStateException when a decision service's `capabilities()` throws
         */
        fun build(): DecisionServiceRegistry = build(log = true)

        internal fun build(log: Boolean): DecisionServiceRegistry {
            val registry = models.build()
            val services = registry.registeredModels()
            val decisionRegistry = DecisionServiceRegistry(
                registry = registry,
                snapshots = java.util.List.copyOf(services.values.map { it.metadata() }),
                candidates = java.util.List.copyOf(candidates.distinct()),
                capabilities = capabilitiesOf(services),
                observationRegistry = observationRegistry,
            )
            if (log) decisionRegistry.logSummary()
            return decisionRegistry
        }

        /**
         * Reads `capabilities()` once for each decision service, for the registry's summary log.
         *
         * @param services the validated registrations to read capabilities from
         * @return each decision service's registration name mapped to its capabilities
         * @throws IllegalStateException when a decision service's capabilities() throws
         */
        private fun capabilitiesOf(services: Map<String, ClassificationService>): Map<String, DecisionCapabilities> =
            services.filterValues { it is DecisionService }.mapValues { (name, service) ->
                try {
                    (service as DecisionService).capabilities()
                } catch (e: Exception) {
                    // The cause's message is left out because it can carry provider text.
                    throw IllegalStateException(
                        "capabilities() of decision service '$name' (name ${service.name}, provider " +
                            "${service.provider}) threw ${e.javaClass.name}. Fix the service's capabilities() " +
                            "or remove the registration '$name'.",
                        e,
                    )
                }
            }.let { java.util.Collections.unmodifiableMap(it) }
    }

    /** Serves a [DecisionModelProvider]'s models to the [ModelRegistry]. Every one is a decision service. */
    private class DecisionModelSource(private val delegate: DecisionModelProvider) : ModelSource<ClassificationService> {
        override val provider: String get() = delegate.provider
        override val type: Class<DecisionService> = DecisionService::class.java
        override fun modelNames(): List<String> = delegate.modelNames()
        override fun modelNamed(name: String): DecisionService? = delegate.decisionServiceNamed(name)
    }

    /**
     * Logs one INFO summary of the built registry: its services, defaults, roles and candidates.
     * Does nothing when INFO logging is disabled.
     */
    private fun logSummary() {
        if (!logger.isInfoEnabled) return
        val entries = registry.registeredModels().entries.joinToString(", ") { (name, service) ->
            val described = capabilities[name]?.let { ", capabilities ${describe(it)}" } ?: ""
            "$name (name ${service.name}, provider ${service.provider}, type ${service.type}$described)"
        }
        logger.info(
            "Decision service registry built with {} services: [{}]; decision default: {}; decision roles: {}; " +
                "classification default: {}; classification roles: {}; default candidates: {}",
            registry.registeredModels().size,
            entries,
            registry.describeDefault(DecisionService::class.java),
            registry.roles(DecisionService::class.java),
            registry.describeDefault(ClassificationService::class.java),
            registry.roles(ClassificationService::class.java),
            candidates,
        )
    }

    /**
     * Formats a decision service's capabilities for the summary log.
     *
     * @param capabilities the capabilities to format
     * @return the formatted capabilities
     */
    private fun describe(capabilities: DecisionCapabilities): String = "kinds ${capabilities.questionKinds}"

    companion object {
        private val logger = LoggerFactory.getLogger(DecisionServiceRegistry::class.java)

        private const val BUILDER = "DecisionServiceRegistry.Builder"

        private val DECISION_FAMILY = ModelRegistry.FamilySpec(
            label = "decision",
            builderName = BUILDER,
            bindAdvice = { name, typeName ->
                "Name a $typeName, or bind '$name' with classificationRole or classificationDefault."
            },
            selectAdvice = { typeName -> "Select it through classifications(), or register a $typeName under that name." },
        )

        private val CLASSIFICATION_FAMILY = ModelRegistry.FamilySpec(
            label = "classification",
            builderName = BUILDER,
            bindAdvice = { name, typeName -> "Name a $typeName, or bind '$name' with decisionRole or decisionDefault." },
            selectAdvice = { typeName -> "Select it through classifications(), or register a $typeName under that name." },
        )

        private val EMPTY: DecisionServiceRegistry by lazy { Builder().build(log = false) }

        /**
         * Returns a new registry builder.
         *
         * @return an empty builder
         */
        @JvmStatic
        fun builder(): Builder = Builder()

        /**
         * Returns a registry with no services. Its selectors support only [ServiceSelector.using].
         *
         * @return the shared empty registry
         */
        @JvmStatic
        fun empty(): DecisionServiceRegistry = EMPTY
    }
}
