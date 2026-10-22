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

import com.embabel.common.ai.model.ServiceSelectionException.Reason
import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Registered models of one kind, grouped into families by [ModelCapability], with a default and role
 * bindings per family.
 *
 * A family is one capability interface. A model belongs to every family whose interface it
 * implements, so a model that can do two things is selectable from both families. Supporting a new
 * kind of model means declaring a family for its capability interface, not writing a registry.
 *
 * The registry is an immutable snapshot taken when [Builder.build] runs. Instances are safe to share
 * between threads.
 *
 * A family default is resolved in this order: the explicit default, then the single default
 * candidate eligible for the family, then the single registered model eligible for the family.
 * Otherwise [ServiceSelector.defaultService] throws [ServiceSelectionException].
 * A name no registered model holds is resolved through the [ModelSource]s, in the order they were
 * added. Each source model is created once, on first lookup. Defaults and roles may name a source
 * model whose type fits the family. Source models are never chosen as a default by the candidate or
 * single-model rules.
 *
 * Usage, with a made-up `ImageService` capability and two registered models:
 * ```kotlin
 * val registry = ModelRegistry.builder<ImageService>()
 *     .family(ImageService::class.java, ModelRegistry.FamilySpec("image", "MyImageRegistry.Builder"))
 *     .register("sketch", sketchModel)
 *     .register("photo", photoModel)
 *     .defaultFor(ImageService::class.java, "photo")
 *     .role(ImageService::class.java, "cheap", "sketch")
 *     .build()
 * val images = registry.select(ImageService::class.java)
 * images.defaultService() // photoModel
 * images.byRole("cheap") // sketchModel
 * ```
 *
 * @param M the kind of model the registry holds
 */
@ApiStatus.Experimental
class ModelRegistry<M : ModelCapability> private constructor(
    private val models: Map<String, M>,
    private val families: Map<Class<*>, Family>,
    private val sources: List<ModelSource<M>>,
    private val describe: (M) -> String,
) {

    // computeIfAbsent stores nothing when no source serves the name, so a later lookup asks again.
    private val provided = ConcurrentHashMap<String, M?>()

    private val selectors: Map<Class<*>, ServiceSelector<*>> =
        families.mapValues { (_, family) -> FamilySelector<ModelCapability>(family) }

    /**
     * Returns the selector for the family of [type].
     *
     * @param type the capability interface the family was declared with
     * @return a selector over the models with that capability
     * @throws IllegalArgumentException when no family was declared for [type]
     */
    fun <S : M> select(type: Class<S>): ServiceSelector<S> {
        val selector = requireNotNull(selectors[type]) {
            "No family is declared for ${type.name}. Declared families: ${families.keys.map { it.simpleName }}."
        }
        @Suppress("UNCHECKED_CAST")
        return selector as ServiceSelector<S>
    }

    /**
     * Returns the registered models by registration name, in registration order.
     *
     * @return an unmodifiable map of registration name to model
     */
    fun registeredModels(): Map<String, M> = models

    /** Describes how the default of [type]'s family was resolved, for summary logs. */
    internal fun describeDefault(type: Class<*>): String = familyOf(type).describeDefault()

    /** The role bindings of [type]'s family, for summary logs. */
    internal fun roles(type: Class<*>): Map<String, String> = familyOf(type).roles

    private fun familyOf(type: Class<*>): Family =
        requireNotNull(families[type]) { "No family is declared for ${type.name}" }

    /**
     * Fixed text and property names for one family.
     *
     * @param label the family's name in messages and properties, such as `decision`
     * @param builderName the builder named in messages that tell a plain Java caller what to call,
     * such as `DecisionServiceRegistry.Builder`. The messages tell the caller to call
     * `register(name, model)`, `<label>Default(name)` and `<label>Role(role, name)` on it, so name a
     * builder that has those methods.
     * @param bindAdvice the advice ending a message about a default or role that names a model of the
     * wrong kind. It is given the model's name and the family type's simple name.
     * @param selectAdvice the advice in a message about selecting a model of the wrong kind by name.
     * It is given the family type's simple name.
     */
    @ApiStatus.Experimental
    class FamilySpec @JvmOverloads constructor(
        val label: String,
        val builderName: String,
        internal val bindAdvice: (name: String, typeName: String) -> String = { _, typeName -> "Name a $typeName." },
        internal val selectAdvice: (typeName: String) -> String = { typeName ->
            "Register a $typeName under that name."
        },
    ) {
        internal val title: String = label.replaceFirstChar { it.uppercase() }
        internal val defaultProperty: String = "embabel.models.$label.default"

        /** Builds the configuration property for one role of this family. */
        internal fun roleProperty(role: String): String = "embabel.models.$label.roles.$role"
    }

    /**
     * Collects registrations, families, defaults, roles, candidates and sources, and validates them in
     * [build]. Instances are not thread-safe.
     */
    @ApiStatus.Experimental
    class Builder<M : ModelCapability> internal constructor() {
        private val registrations = mutableListOf<Registration<M>>()
        private val declared = LinkedHashMap<Class<*>, DeclaredFamily>()
        private val candidates = mutableListOf<String>()
        private val sources = mutableListOf<ModelSource<M>>()
        private var describe: (M) -> String = { it.javaClass.simpleName }

        /** Registers [model] under [name]. */
        fun register(name: String, model: M): Builder<M> = apply { registrations += Registration(name, model) }

        /** Declares a family for the capability interface [type]. */
        fun family(type: Class<out M>, spec: FamilySpec): Builder<M> = apply {
            require(type !in declared) { "a family is already declared for ${type.name}" }
            declared[type] = DeclaredFamily(type, spec)
        }

        /** Sets the default of [type]'s family to the model registered or served under [name]. */
        fun defaultFor(type: Class<out M>, name: String): Builder<M> = apply { declaredFamily(type).defaults += name }

        /** Binds [role] in [type]'s family to the model registered or served under [name]. */
        fun role(type: Class<out M>, role: String, name: String): Builder<M> = apply {
            declaredFamily(type).roles += RoleBinding(role, name)
        }

        /** Offers the registered model [name] as a default for every family it is eligible for. */
        fun defaultCandidate(name: String): Builder<M> = apply { candidates += name }

        /** Adds a source of models created on demand. */
        fun source(source: ModelSource<M>): Builder<M> = apply { sources += source }

        /** Sets how a model is described in messages, such as `DECISION`. Defaults to its class name. */
        fun describeWith(describe: (M) -> String): Builder<M> = apply { this.describe = describe }

        /**
         * Validates the collected configuration and builds the registry.
         *
         * @throws IllegalArgumentException when a registration, default, role or candidate is invalid
         */
        fun build(): ModelRegistry<M> {
            val models = validatedModels()
            val sources = java.util.List.copyOf(sources)
            val sourceModels = lazy { sourceModels(sources, models) }
            val families = declared.values.map { family(it, models, sourceModels) }
            val candidateNames = candidates.distinct()
            candidateNames.forEach { candidate ->
                require(candidate.isNotBlank()) { "default candidate name must not be blank" }
                require(candidate in models) {
                    "default candidate '$candidate' names unknown service. Registered services: ${models.keys.toList()}."
                }
            }
            return ModelRegistry(
                models = Collections.unmodifiableMap(LinkedHashMap(models)),
                families = families.associateBy { it.type }.mapValues { (_, family) -> family.withDefault(candidateNames) },
                sources = sources,
                describe = describe,
            )
        }

        private fun declaredFamily(type: Class<*>): DeclaredFamily =
            requireNotNull(declared[type]) { "declare the family for ${type.name} before its defaults and roles" }

        /**
         * Groups model names by source, excluding names held by registered models and keeping names
         * from the first source that supplies them.
         */
        private fun sourceModels(sources: List<ModelSource<M>>, models: Map<String, M>): SourceModels {
            val claimedModelNames = HashSet<String>(models.keys)
            return SourceModels(
                // add returns false for a name a registered model or an earlier source already has,
                // so the filter keeps only names this source is the first to serve.
                sources.map { source -> SourceGroup(source.provider, source.type, source.modelNames().filter(claimedModelNames::add)) }
                    // Drop a source whose names are all taken, so messages do not list it with no names.
                    .filter { it.names.isNotEmpty() },
            )
        }

        /**
         * Validates the registrations and turns them into a name-to-model map.
         *
         * @throws IllegalArgumentException when a name is blank, repeated, or the same instance is
         * registered under two names
         */
        private fun validatedModels(): Map<String, M> {
            val models = LinkedHashMap<String, M>()
            val namesByInstance = IdentityHashMap<M, String>()
            registrations.forEach { (name, model) ->
                require(name.isNotBlank()) { "service registration name must not be blank" }
                require(name !in models) { "service name '$name' is registered more than once" }
                // The map compares models by identity, so two equal but distinct models may both register.
                namesByInstance[model]?.let { first ->
                    throw IllegalArgumentException(
                        "the same service instance is registered as '$first' and '$name'. Register each instance under one name.",
                    )
                }
                models[name] = model
                namesByInstance[model] = name
            }
            return models
        }

        /** Builds one family's eligible models, default and role bindings, with its default unresolved. */
        private fun family(
            declared: DeclaredFamily,
            models: Map<String, M>,
            sourceModels: Lazy<SourceModels>,
        ): Family {
            val spec = declared.spec
            val type = declared.type
            val explicitDefault = declared.defaults.distinct().let { distinct ->
                require(distinct.size <= 1) {
                    "${spec.label} default (${spec.defaultProperty}) is set to more than one service: $distinct"
                }
                distinct.firstOrNull()
            }
            explicitDefault?.let { name ->
                require(name.isNotBlank()) { "${spec.label} default (${spec.defaultProperty}) must not be blank" }
                requireEligible("${spec.label} default", spec.defaultProperty, name, declared, models, sourceModels)
            }
            val roles = LinkedHashMap<String, String>()
            declared.roles.forEach { (role, name) ->
                require(role.isNotBlank()) { "${spec.label} role name must not be blank" }
                val binding = "${spec.label} role '$role'"
                val property = spec.roleProperty(role)
                require(name.isNotBlank()) { "$binding ($property) must name a service" }
                roles[role]?.let { bound ->
                    require(bound == name) { "$binding ($property) is bound to both '$bound' and '$name'" }
                }
                requireEligible(binding, property, name, declared, models, sourceModels)
                roles[role] = name
            }
            return Family(
                spec = spec,
                type = type,
                eligible = java.util.List.copyOf(models.filterValues { type.isInstance(it) }.keys),
                sourceModels = sourceModels,
                roles = Collections.unmodifiableMap(roles),
                explicitDefault = explicitDefault,
                defaultState = DefaultState.Unresolved(Reason.NO_DEFAULT),
            )
        }

        /**
         * Fails unless the named model is registered and has the family's capability, or a source
         * whose type fits the family serves a model under that name.
         */
        private fun requireEligible(
            binding: String,
            property: String,
            name: String,
            declared: DeclaredFamily,
            models: Map<String, M>,
            sourceModels: Lazy<SourceModels>,
        ) {
            val spec = declared.spec
            val type = declared.type
            // A name no registered model holds is valid when a source of a fitting type serves it.
            if (name !in models && name in sourceModels.value.namesFitting(type)) return
            // A name a source serves reaches here only when that source's models lack the family's capability.
            if (name !in models) {
                sourceModels.value.typeServing(name)?.let { sourceType ->
                    throw IllegalArgumentException(
                        "$binding names provider model '$name' (property $property), which is a ${sourceType.simpleName} without ${spec.label} support. ${spec.bindAdvice(name, type.simpleName)}",
                    )
                }
            }
            // Here the name is either registered or served by no source.
            val model = requireNotNull(models[name]) {
                "$binding names unknown service '$name' (property $property). Registered services: ${models.keys.toList()}. Provider models: ${sourceModels.value.describeFitting(type)}."
            }
            require(type.isInstance(model)) {
                "$binding names service '$name' (property $property), which is a ${describe(model)} service without ${spec.label} support. ${spec.bindAdvice(name, type.simpleName)}"
            }
        }

        private data class Registration<M>(val name: String, val model: M)

        private data class RoleBinding(val role: String, val name: String)

        private class DeclaredFamily(val type: Class<*>, val spec: FamilySpec) {
            val defaults = mutableListOf<String>()
            val roles = mutableListOf<RoleBinding>()
        }
    }

    /**
     * One source's name, the type of its models, and the model names it serves. The name is only
     * printed, and is nullable because a source implemented in Java can return null for it.
     */
    private class SourceGroup(val provider: String?, val type: Class<*>, val names: List<String>)

    /** The model names each source serves, for eligibility checks and diagnostic messages. */
    private class SourceModels(private val groups: List<SourceGroup>) {

        /** The names served by sources whose models have [type]. */
        fun namesFitting(type: Class<*>): List<String> = fitting(type).flatMap { it.names }

        /** The type of the source that serves [name], or null when no source serves it. */
        fun typeServing(name: String): Class<*>? = groups.firstOrNull { name in it.names }?.type

        /** Lists the models fitting [type] by provider, for example `llm [a, b]; TypeSafe [c]`, or `none`. */
        fun describeFitting(type: Class<*>): String = describe(fitting(type))

        private fun fitting(type: Class<*>) = groups.filter { type.isAssignableFrom(it.type) }

        private fun describe(groups: List<SourceGroup>): String =
            if (groups.isEmpty()) "none" else groups.joinToString("; ") { "${it.provider} ${it.names}" }
    }

    /** How a family default was chosen, or why none could be chosen. */
    private sealed interface DefaultState {
        data class Resolved(val name: String, val source: String) : DefaultState
        data class Unresolved(val reason: Reason, val detail: String = "") : DefaultState
    }

    private class Family(
        val spec: FamilySpec,
        val type: Class<*>,
        val eligible: List<String>,
        val sourceModels: Lazy<SourceModels>,
        val roles: Map<String, String>,
        val explicitDefault: String?,
        val defaultState: DefaultState,
        val candidates: List<String> = emptyList(),
    ) {
        /**
         * Resolves the family's default from the explicit default, then the eligible default
         * candidates, then the eligible models, and returns a copy of this family with that
         * resolution recorded.
         */
        fun withDefault(candidates: List<String>): Family {
            // A candidate counts only for a family whose capability its model has.
            val eligibleCandidates = candidates.filter { it in eligible }
            val state = when {
                explicitDefault != null -> DefaultState.Resolved(explicitDefault, "explicit")
                eligibleCandidates.size == 1 -> DefaultState.Resolved(eligibleCandidates.single(), "default candidate")
                eligibleCandidates.size > 1 -> DefaultState.Unresolved(
                    Reason.AMBIGUOUS_DEFAULT,
                    "No default ${spec.label} service is set and ${eligibleCandidates.size} default candidates are eligible: $eligibleCandidates.",
                )
                eligible.size == 1 -> DefaultState.Resolved(eligible.single(), "only eligible service")
                eligible.isEmpty() -> DefaultState.Unresolved(
                    Reason.NO_DEFAULT,
                    "No default ${spec.label} service is set, no default candidate is eligible, and no ${spec.label} service is registered.",
                )
                else -> DefaultState.Unresolved(
                    Reason.AMBIGUOUS_DEFAULT,
                    "No default ${spec.label} service is set, no default candidate is eligible, and ${eligible.size} ${spec.label} services are eligible: $eligible.",
                )
            }
            return Family(spec, type, eligible, sourceModels, roles, explicitDefault, state, candidates)
        }

        /** Describes how the family's default was resolved, or why it wasn't, for diagnostic messages. */
        fun describeDefault(): String = when (val state = defaultState) {
            is DefaultState.Resolved -> "'${state.name}' (${state.source})"
            is DefaultState.Unresolved -> "none set; candidates considered: $candidates"
        }

        /** The models, source models, roles and default of this family, for diagnostic messages. */
        fun context(): String {
            // Listing source models is best effort here: a failure must not replace the selection error.
            val models = runCatching { sourceModels.value }.fold(
                // Source models are listed only when some of them fit this family.
                onSuccess = { if (it.namesFitting(type).isEmpty()) "" else " Provider models: ${it.describeFitting(type)}." },
                onFailure = { " Provider models could not be listed." },
            )
            return "${spec.title} services: $eligible.$models ${spec.title} roles: $roles. ${spec.title} default: ${describeDefault()}."
        }
    }

    private inner class FamilySelector<S : ModelCapability>(
        private val family: Family,
    ) : ServiceSelector<S> {
        private val spec = family.spec

        override fun defaultService(): S = when (val state = family.defaultState) {
            is DefaultState.Resolved -> lookup(state.name)
            is DefaultState.Unresolved -> throw ServiceSelectionException(
                state.reason,
                "${state.detail} ${family.context()} Set ${spec.defaultProperty}=<name>, or call ${spec.builderName}.${spec.label}Default(name) in plain Java.",
            )
        }

        override fun named(name: String): S {
            // A registered model wins over a source model of the same name.
            val model = models[name] ?: sourceModel(name) ?: throw ServiceSelectionException(
                Reason.UNKNOWN_NAME,
                "No ${spec.label} service is registered under the name '$name'. ${family.context()} Register a service under that name in configuration or with ${spec.builderName}.register(name, service), or select one of the listed names.",
            )
            // The lookup above finds models of every capability, so check that this one fits the family.
            return requireFits(name, model)
        }

        override fun byRole(role: String): S {
            val name = family.roles[role] ?: throw ServiceSelectionException(
                Reason.UNKNOWN_ROLE,
                "No ${spec.label} service is bound to the role '$role'. ${family.context()} Bind the role with ${spec.roleProperty(role)}=<name>, or call ${spec.builderName}.${spec.label}Role(\"$role\", name) in plain Java.",
            )
            return lookup(name)
        }

        override fun using(service: S): S = service

        /**
         * Returns the model a default or role names. [Builder.build] checked that the source listing
         * the name first fits the family, but when that source returns nothing a later source of
         * another type can serve the name, so the model is checked again here.
         */
        private fun lookup(name: String): S = requireFits(
            name,
            models[name] ?: sourceModel(name) ?: throw ServiceSelectionException(
                Reason.UNKNOWN_NAME,
                "No provider returned a service for the model '$name'. ${family.context()}",
            ),
        )

        /** Returns [model] as this family's type, or fails with WRONG_CAPABILITY when it lacks the capability. */
        private fun requireFits(name: String, model: M): S {
            if (!family.type.isInstance(model)) {
                throw ServiceSelectionException(
                    Reason.WRONG_CAPABILITY,
                    "Service '$name' is a ${describe(model)} service (${model.javaClass.name}) and cannot answer ${spec.label} requests. ${spec.selectAdvice(family.type.simpleName)} ${family.context()}",
                )
            }
            return cast(model)
        }

        /** The family's type is S: [select] hands this selector out only under that type. */
        @Suppress("UNCHECKED_CAST")
        private fun cast(model: M): S = family.type.cast(model) as S
    }

    /** Returns the model a source creates for a name, creating it on the first lookup. */
    private fun sourceModel(name: String): M? =
        provided.computeIfAbsent(name) { sources.firstNotNullOfOrNull { it.modelNamed(name) } }

    companion object {

        /** Returns a new registry builder. */
        @JvmStatic
        fun <M : ModelCapability> builder(): Builder<M> = Builder()
    }
}
