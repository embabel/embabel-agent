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

import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.model.ServiceSelectionException.Reason
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

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
 */
@ApiStatus.Experimental
class DecisionServiceRegistry private constructor(
    private val services: Map<String, ClassificationService>,
    private val snapshots: List<ClassificationServiceMetadata>,
    private val decisionFamily: Family<DecisionService>,
    private val classificationFamily: Family<ClassificationService>,
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

    private val decisionSelector: ServiceSelector<DecisionService> = FamilySelector(decisionFamily)
    private val classificationSelector: ServiceSelector<ClassificationService> =
        FamilySelector(classificationFamily)

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
    fun registrationNames(): List<String> = java.util.List.copyOf(services.keys)

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
        private val registrations = mutableListOf<Pair<String, ClassificationService>>()
        private val decisionDefaults = mutableListOf<String>()
        private val classificationDefaults = mutableListOf<String>()
        private val decisionRoles = mutableListOf<Pair<String, String>>()
        private val classificationRoles = mutableListOf<Pair<String, String>>()
        private val candidates = mutableListOf<String>()
        private var observationRegistry: ObservationRegistry = ObservationRegistry.NOOP

        /**
         * Registers [service] under [name].
         *
         * @param name the registration name, unique in the registry
         * @param service the service to register
         * @return this builder
         */
        fun register(name: String, service: ClassificationService): Builder = apply {
            registrations += name to service
        }

        /**
         * Sets the decision family default.
         *
         * @param name the registration name of a decision service
         * @return this builder
         */
        fun decisionDefault(name: String): Builder = apply { decisionDefaults += name }

        /**
         * Sets the classification family default.
         *
         * @param name the registration name of any registered service
         * @return this builder
         */
        fun classificationDefault(name: String): Builder = apply { classificationDefaults += name }

        /**
         * Binds [role] in the decision family to the service registered under [name].
         *
         * @param role the role name
         * @param name the registration name of a decision service
         * @return this builder
         */
        fun decisionRole(role: String, name: String): Builder = apply { decisionRoles += role to name }

        /**
         * Binds [role] in the classification family to the service registered under [name].
         *
         * @param role the role name
         * @param name the registration name of any registered service
         * @return this builder
         */
        fun classificationRole(role: String, name: String): Builder = apply {
            classificationRoles += role to name
        }

        /**
         * Offers the service registered under [name] as a family default candidate.
         *
         * @param name the registration name of the candidate
         * @return this builder
         */
        fun defaultCandidate(name: String): Builder = apply { candidates += name }

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
            val services = validatedServices()
            val decisionEligible = services.filterValues { it is DecisionService }.keys.toList()
            val registered = services.keys.toList()
            val decisionFamily = family(
                DECISION_FAMILY, DecisionService::class.java, services, decisionEligible,
                decisionDefaults, decisionRoles,
            )
            val classificationFamily = family(
                CLASSIFICATION_FAMILY, ClassificationService::class.java, services, registered,
                classificationDefaults, classificationRoles,
            )
            val candidateNames = candidates.distinct()
            candidateNames.forEach { candidate ->
                require(candidate.isNotBlank()) { "default candidate name must not be blank" }
                require(candidate in services) {
                    "default candidate '$candidate' names unknown service. Registered services: $registered."
                }
            }
            val registry = DecisionServiceRegistry(
                services = Collections.unmodifiableMap(LinkedHashMap(services)),
                snapshots = java.util.List.copyOf(services.values.map { it.metadata() }),
                decisionFamily = decisionFamily.withDefault(candidateNames),
                classificationFamily = classificationFamily.withDefault(candidateNames),
                candidates = java.util.List.copyOf(candidateNames),
                capabilities = capabilitiesOf(services),
                observationRegistry = observationRegistry,
            )
            if (log) registry.logSummary()
            return registry
        }

        private fun capabilitiesOf(services: Map<String, ClassificationService>): Map<String, DecisionCapabilities> {
            val capabilities = LinkedHashMap<String, DecisionCapabilities>()
            services.forEach { (name, service) ->
                if (service !is DecisionService) return@forEach
                capabilities[name] = try {
                    service.capabilities()
                } catch (e: Exception) {
                    // The cause's message is left out because it can carry provider text.
                    throw IllegalStateException(
                        "capabilities() of decision service '$name' (name ${service.name}, provider " +
                            "${service.provider}) threw ${e.javaClass.name}. Fix the service's capabilities() " +
                            "or remove the registration '$name'.",
                        e,
                    )
                }
            }
            return Collections.unmodifiableMap(capabilities)
        }

        private fun validatedServices(): Map<String, ClassificationService> {
            val services = LinkedHashMap<String, ClassificationService>()
            val namesByInstance = IdentityHashMap<ClassificationService, String>()
            registrations.forEach { (name, service) ->
                require(name.isNotBlank()) { "service registration name must not be blank" }
                require(name !in services) { "service name '$name' is registered more than once" }
                namesByInstance[service]?.let { first ->
                    throw IllegalArgumentException(
                        "the same service instance is registered as '$first' and '$name'. " +
                            "Register each instance under one name.",
                    )
                }
                services[name] = service
                namesByInstance[service] = name
            }
            return services
        }

        private fun <S : ClassificationService> family(
            spec: FamilySpec,
            type: Class<S>,
            services: Map<String, ClassificationService>,
            eligible: List<String>,
            defaults: List<String>,
            roleBindings: List<Pair<String, String>>,
        ): Family<S> {
            val registered = services.keys.toList()
            val explicit = defaults.distinct().let { distinct ->
                require(distinct.size <= 1) {
                    "${spec.label} default (${spec.defaultProperty}) is set to more than one service: $distinct"
                }
                distinct.firstOrNull()
            }
            explicit?.let { name ->
                require(name.isNotBlank()) { "${spec.label} default (${spec.defaultProperty}) must not be blank" }
                requireEligible("${spec.label} default", spec.defaultProperty, name, spec, type, services, registered)
            }
            val roles = LinkedHashMap<String, String>()
            roleBindings.forEach { (role, name) ->
                require(role.isNotBlank()) { "${spec.label} role name must not be blank" }
                val binding = "${spec.label} role '$role'"
                val property = spec.roleProperty(role)
                require(name.isNotBlank()) { "$binding ($property) must name a service" }
                roles[role]?.let { bound ->
                    require(bound == name) { "$binding ($property) is bound to both '$bound' and '$name'" }
                }
                requireEligible(binding, property, name, spec, type, services, registered)
                roles[role] = name
            }
            return Family(
                spec = spec,
                type = type,
                eligible = java.util.List.copyOf(eligible),
                roles = Collections.unmodifiableMap(roles),
                explicitDefault = explicit,
                defaultState = DefaultState.Unresolved(Reason.NO_DEFAULT),
            )
        }

        private fun requireEligible(
            binding: String,
            property: String,
            name: String,
            spec: FamilySpec,
            type: Class<*>,
            services: Map<String, ClassificationService>,
            registered: List<String>,
        ) {
            val service = requireNotNull(services[name]) {
                "$binding names unknown service '$name' (property $property). Registered services: $registered."
            }
            require(type.isInstance(service)) {
                "$binding names service '$name' (property $property), which is a ${service.type} service without " +
                    "${spec.label} support. " +
                    "Name a ${type.simpleName}, or bind '$name' with ${spec.otherBuilderPrefix}Role " +
                    "or ${spec.otherBuilderPrefix}Default."
            }
        }
    }

    /** Fixed text and property names for one family. */
    private class FamilySpec(
        val label: String,
        val builderPrefix: String,
        val otherBuilderPrefix: String,
    ) {
        val title: String = label.replaceFirstChar { it.uppercase() }
        val defaultProperty: String = "embabel.models.$label.default"
        fun roleProperty(role: String): String = "embabel.models.$label.roles.$role"
    }

    /** How a family default was chosen, or why none could be chosen. */
    private sealed interface DefaultState {
        data class Resolved(val name: String, val source: String) : DefaultState
        data class Unresolved(val reason: Reason, val detail: String = "") : DefaultState
    }

    private class Family<S : ClassificationService>(
        val spec: FamilySpec,
        val type: Class<S>,
        val eligible: List<String>,
        val roles: Map<String, String>,
        val explicitDefault: String?,
        val defaultState: DefaultState,
        val candidates: List<String> = emptyList(),
    ) {
        fun withDefault(candidates: List<String>): Family<S> {
            val eligibleCandidates = candidates.filter { it in eligible }
            val state = when {
                explicitDefault != null -> DefaultState.Resolved(explicitDefault, "explicit")
                eligibleCandidates.size == 1 -> DefaultState.Resolved(eligibleCandidates.single(), "default candidate")
                eligibleCandidates.size > 1 -> DefaultState.Unresolved(
                    Reason.AMBIGUOUS_DEFAULT,
                    "No default ${spec.label} service is set and ${eligibleCandidates.size} default candidates " +
                        "are eligible: $eligibleCandidates.",
                )
                eligible.size == 1 -> DefaultState.Resolved(eligible.single(), "only eligible service")
                eligible.isEmpty() -> DefaultState.Unresolved(
                    Reason.NO_DEFAULT,
                    "No default ${spec.label} service is set, no default candidate is eligible, and no " +
                        "${spec.label} service is registered.",
                )
                else -> DefaultState.Unresolved(
                    Reason.AMBIGUOUS_DEFAULT,
                    "No default ${spec.label} service is set, no default candidate is eligible, and " +
                        "${eligible.size} ${spec.label} services are eligible: $eligible.",
                )
            }
            return Family(spec, type, eligible, roles, explicitDefault, state, candidates)
        }

        fun describeDefault(): String = when (val state = defaultState) {
            is DefaultState.Resolved -> "'${state.name}' (${state.source})"
            is DefaultState.Unresolved -> "none set; candidates considered: $candidates"
        }

        /** The services, roles and default of this family, for diagnostic messages. */
        fun context(): String =
            "${spec.title} services: $eligible. ${spec.title} roles: $roles. ${spec.title} default: ${describeDefault()}."
    }

    private inner class FamilySelector<S : ClassificationService>(
        private val family: Family<S>,
    ) : ServiceSelector<S> {
        private val spec = family.spec

        override fun defaultService(): S = when (val state = family.defaultState) {
            is DefaultState.Resolved -> lookup(state.name)
            is DefaultState.Unresolved -> throw ServiceSelectionException(
                state.reason,
                "${state.detail} ${family.context()} Set ${spec.defaultProperty}=<name>, or call " +
                    "DecisionServiceRegistry.Builder.${spec.builderPrefix}Default(name) in plain Java.",
            )
        }

        override fun named(name: String): S {
            val service = services[name] ?: throw ServiceSelectionException(
                Reason.UNKNOWN_NAME,
                "No ${spec.label} service is registered under the name '$name'. ${family.context()} Register a " +
                    "service under that name in configuration or with DecisionServiceRegistry.Builder.register(" +
                    "name, service), or select one of the listed names.",
            )
            if (!family.type.isInstance(service)) {
                throw ServiceSelectionException(
                    Reason.WRONG_CAPABILITY,
                    "Service '$name' is a ${service.type} service (${service.javaClass.name}) and cannot answer " +
                        "${spec.label} requests. Select it through classifications(), or register a " +
                        "${family.type.simpleName} under that name. ${family.context()}",
                )
            }
            return family.type.cast(service)
        }

        override fun byRole(role: String): S {
            val name = family.roles[role] ?: throw ServiceSelectionException(
                Reason.UNKNOWN_ROLE,
                "No ${spec.label} service is bound to the role '$role'. ${family.context()} Bind the role with " +
                    "${spec.roleProperty(role)}=<name>, or call DecisionServiceRegistry.Builder." +
                    "${spec.builderPrefix}Role(\"$role\", name) in plain Java.",
            )
            return lookup(name)
        }

        override fun using(service: S): S = service

        private fun lookup(name: String): S = family.type.cast(services.getValue(name))
    }

    private fun logSummary() {
        if (!logger.isInfoEnabled) return
        val entries = services.entries.joinToString(", ") { (name, service) ->
            val described = capabilities[name]?.let { ", capabilities ${describe(it)}" } ?: ""
            "$name (name ${service.name}, provider ${service.provider}, type ${service.type}$described)"
        }
        logger.info(
            "Decision service registry built with {} services: [{}]; decision default: {}; decision roles: {}; " +
                "classification default: {}; classification roles: {}; default candidates: {}",
            services.size,
            entries,
            decisionFamily.describeDefault(),
            decisionFamily.roles,
            classificationFamily.describeDefault(),
            classificationFamily.roles,
            candidates,
        )
    }

    private fun describe(capabilities: DecisionCapabilities): String =
        "kinds ${capabilities.questionKinds}, " +
            "max questions ${capabilities.maxQuestions ?: "none"}, " +
            "max input characters ${capabilities.maxInputCharacters ?: "none"}"

    companion object {
        private val logger = LoggerFactory.getLogger(DecisionServiceRegistry::class.java)

        private val DECISION_FAMILY = FamilySpec("decision", "decision", "classification")
        private val CLASSIFICATION_FAMILY = FamilySpec("classification", "classification", "decision")

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
