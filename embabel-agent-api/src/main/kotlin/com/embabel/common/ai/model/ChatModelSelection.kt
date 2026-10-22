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

import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.PlaceholderLlmService
import com.embabel.common.ai.model.local.LocalModelCatalog
import com.embabel.common.util.loggerFor

/**
 * How [ConfigurableModelProvider] selects chat models: the deployment default, roles, names, and
 * what happens when nothing satisfies a request.
 *
 * The mechanics every kind shares - resolvers, credentials, local runners - are [family]'s. What is
 * here is chat's own policy: an unsatisfied role hands back the placeholder while the deployment
 * awaits a key and throws otherwise, and a role carries [LlmOptions] that merge into the call.
 *
 * @param llms the chat models registered at startup
 * @param properties the bound `embabel.models` configuration
 * @param applicationResolvers the application's chat role resolvers, in order
 * @param modelPlatforms platforms that build models from a user's key; asked for chat
 * @param localModelCatalogs what local runners are serving
 * @param lateArrivingProviders providers whose models may appear after startup
 */
internal class ChatModelSelection(
    private val llms: List<LlmService<*>>,
    private val properties: ConfigurableModelProviderProperties,
    applicationResolvers: List<RoleResolver<LlmService<*>, LlmOptions>>,
    modelPlatforms: List<ModelPlatform>,
    localModelCatalogs: List<LocalModelCatalog>,
    private val lateArrivingProviders: Set<String>,
) {

    private val logger = loggerFor<ConfigurableModelProvider>()

    /**
     * Application resolvers first, the configuration-driven one last, so an application can
     * override any role and ignore the rest.
     */
    val family: ModelFamily<LlmService<*>, LlmOptions> = ModelFamily(
        kind = LlmService.KIND,
        registered = llms,
        roles = properties.chatRoles(),
        resolvers = applicationResolvers +
            ConfigurableRoleResolver(LlmService.KIND, properties.chatRoles()) { defaultLlm.provider },
        modelPlatforms = modelPlatforms,
        catalogs = localModelCatalogs,
        credentialCacheSize = properties.credentialServiceCacheSize,
        rolesProperty = "embabel.models.roles or embabel.models.llms",
        isPlaceholder = { it is PlaceholderLlmService },
    )

    /**
     * The registered service `default-llm` names directly, or null when it names a role - or a
     * model nothing has registered.
     *
     * Null is what sends the default through the role chain on every call, so it is also the thing
     * that keeps a *registered* default off that path: a name that resolves here is resolved once,
     * at startup, exactly as before.
     */
    private val registeredDefaultLlm: LlmService<*>? = llms.firstOrNull { it.name == properties.defaultLlm }

    /**
     * What the default resolves to when nothing better is available - the deployment's provider for
     * role resolution, and the answer to [DefaultModelSelectionCriteria] when the role chain
     * declines.
     *
     * Resolved against whichever role map names a model this deployment has registered: the flat
     * `llms` map first, since that is the single-provider shape and says what the default means
     * without further qualification, then the nested `roles` map.
     */
    private val defaultLlm =
        if (llms.isNotEmpty())
            registeredDefaultLlm
                ?: flatRoleDefaultLlm()
                ?: nestedRoleDefaultLlm()
                ?: placeholderLlm()
                ?: throw IllegalArgumentException(unresolvableDefaultLlmMessage())
        else
            throw IllegalArgumentException("No models detected. Ensure that at least one Embabel Agent Starter (e.g. embabel-agent-starter-openai) is on the classpath and models are loaded into it.")

    /**
     * The registered service that `default-llm` names via the flat role map, if it names a role
     * that one of them satisfies.
     */
    private fun flatRoleDefaultLlm(): LlmService<*>? =
        properties.llms[properties.defaultLlm]?.let { model -> llms.firstOrNull { it.name == model } }

    /**
     * The registered service a nested `roles` entry for `default-llm` names, taking whichever
     * provider column this deployment can actually serve.
     *
     * The provider cannot be used to pick the column, because the provider is what this value is
     * being computed to supply. Taking whichever column a registered model answers for is the same
     * question from the other end: a deployment can only serve the providers it has models for, and
     * where it has several, any of them is a defensible startup default. Per-call resolution still
     * picks the right column for whichever key is active.
     *
     * The nested shape is not only for deployments awaiting a key. One that holds its own key may
     * use it to say what each role means per provider, against the day it serves users who bring
     * theirs - and such a deployment has no placeholder to fall back to. Without this it could not
     * name a role as its default at all: it failed to start, and was told the role it had just
     * configured was not a role.
     */
    private fun nestedRoleDefaultLlm(): LlmService<*>? =
        properties.roles[properties.defaultLlm]
            ?.values
            ?.mapNotNull { it.modelName }
            ?.firstNotNullOfOrNull { model -> llms.firstOrNull { it.name == model } }

    /**
     * Why `default-llm` resolved to nothing, said in the terms the reader has to act on.
     *
     * A configured role that nothing satisfies is a different problem from a name that is not a
     * role at all, and telling someone their role is not a role sends them to fix the one thing
     * that is already right.
     */
    private fun unresolvableDefaultLlmMessage(): String =
        if (properties.defaultLlmNamesRole())
            "Default LLM '${properties.defaultLlm}' is a configured role, but nothing this deployment has registered satisfies it. Point the role at one of the available models: ${llms.map { it.name }}, or set 'embabel.models.default-llm' to one of them directly."
        else
            "Default LLM '${properties.defaultLlm}' is neither a registered model nor a configured role. Set the 'embabel.models.default-llm' property to one of the available models: ${llms.map { it.name }}, or to one of the roles: ${(properties.llms.keys + properties.roles.keys).toList()}."

    /**
     * Whether this deployment is waiting for a key rather than misconfigured.
     *
     * True exactly when `default-llm` resolved to a [PlaceholderLlmService] - either because it
     * names one, or because the model it names is not registered and a placeholder stands in. That
     * is the deployment stating that keys arrive at runtime, and it is the only thing that makes an
     * unresolvable model name in configuration expected rather than a typo.
     *
     * A deployment that has a key resolves `default-llm` to a real model and so is never in this
     * mode, even with a placeholder registered alongside - which is what a BYOK starter next to a
     * provider starter looks like.
     */
    val setupRequired: Boolean = defaultLlm is PlaceholderLlmService

    /**
     * The registered placeholder, if this deployment carries one.
     *
     * Deliberately structural rather than by name: `com.embabel.agent.spi` owns the marker, and
     * this class must not depend on the BYOK module that implements it.
     */
    private fun placeholderLlm(): LlmService<*>? =
        family.placeholder()
            ?.also {
                // Named, because degrading a real model to the placeholder would otherwise hide the
                // case where the key IS set and the model simply failed to register.
                //
                // A role is reported differently: it has not failed to resolve, it simply resolves
                // per call, and the placeholder is only what it falls back to on a call nothing can
                // satisfy. Warning about it at startup would fire on every boot of exactly the
                // deployment this is meant to serve.
                if (properties.defaultLlmNamesRole()) {
                    logger.info(
                        """
                        Default LLM '{}' is a role, and will be resolved per call.
                        Until a key is supplied it falls back to the '{}' placeholder
                        """.trimIndent(),
                        properties.defaultLlm, it.name,
                    )
                } else {
                    logger.warn(
                        """
                        Default LLM '{}' is not registered; falling back to the '{}' placeholder.
                        Calls will fail with an actionable 'no LLM configured' error until a key is supplied. Available: {}
                        """.trimIndent(),
                        properties.defaultLlm, it.name, llms.map { it.name },
                    )
                }
            }

    /**
     * Fail on a flat `llms` entry or a nested `roles` entry this deployment cannot satisfy - unless
     * it is awaiting a key, when the same entry is expected and only reported.
     */
    fun validate() {
        properties.llms.forEach { (role, model) ->
            if (llms.none { it.name == model }) {
                // Fatal, unless this deployment is waiting for a key. A name that resolves to
                // nothing is a typo in a deployment that has one, and letting it start would move
                // the failure to whichever unrelated call first asks for that role. A deployment in
                // setup-required mode has no models registered yet by definition, so the same name
                // is expected there and only worth reporting.
                if (setupRequired) {
                    logger.warn(
                        """
                        LLM '{}' for role '{}' is not registered. This deployment is awaiting a key, so that is expected;
                        the role will report 'no LLM configured' until one is supplied. Available: {}
                        """.trimIndent(),
                        model, role, llms.map { it.name },
                    )
                } else {
                    error("LLM '$model' for role $role is not available: Choices are ${llms.map { it.name }}")
                }
            }
        }
        checkNestedRoles()
    }

    /**
     * Warn about `embabel.models.roles` entries that cannot be satisfied, on the same terms as
     * the flat map above.
     *
     * Only entries for the deployment's own provider are checked. An entry for a provider this
     * deployment is not keyed for is the point of the nested shape - it applies when a user
     * brings a key for that provider, and its model is not expected to be registered here, so
     * warning about it would train people to ignore the warning.
     *
     * A provider that has declared [LateArrivingModels] is excused for the same reason one step
     * later: its models are pulled on the host, so a role naming one nobody has pulled yet is the
     * ordinary state of an appliance before setup, and refusing to start is refusing to reach the
     * point where the operator could pull it.
     *
     * Without this, a typo under `roles` is silent until something asks for the role: the entry
     * is found, its model is not registered, and resolution throws rather than falling back to
     * the flat map - which is the one case where the nested shape can take a role AWAY.
     */
    private fun checkNestedRoles() {
        val deploymentProvider = defaultLlm.provider
        properties.roles.forEach { (role, byProvider) ->
            byProvider
                .filterKeys { it.equals(deploymentProvider, ignoreCase = true) }
                .forEach { (provider, options) ->
                    val model = options.modelName
                    if (provider.lowercase() in lateArrivingProviders) {
                        // Reported, never silent: the entry has been EXCUSED from a check that would
                        // otherwise have stopped the deployment, and an operator reading a boot log
                        // to work out why a role does nothing needs to see that.
                        if (model == null || llms.none { it.name == model }) {
                            logger.info(
                                "Role '{}' names model '{}' under provider '{}', whose models may arrive after startup; it will resolve once that model is served",
                                role, model ?: "<unspecified>", provider,
                            )
                        }
                        return@forEach
                    }
                    if (model == null) {
                        reportUnsatisfiableRole(
                            "Role '$role' under provider '$provider' names no model, so anything asking for that role will fail",
                        )
                    } else if (llms.none { it.name == model }) {
                        reportUnsatisfiableRole(
                            """
                            LLM '$model' for role '$role' under provider '$provider' - this deployment's own provider - is not available. Available: ${llms.map { it.name }}
                            """.trimIndent(),
                        )
                    }
                }
        }
    }

    /**
     * Report a role this deployment cannot satisfy, on the same terms as the flat map: fatal in a
     * deployment that holds a key, expected in one that is waiting for one.
     *
     * Shared so the two shapes cannot drift. Applying the rule to only one of them was the original
     * defect here - a typo under `roles` warned and booted while the same typo under `llms` did not.
     */
    private fun reportUnsatisfiableRole(message: String) {
        if (setupRequired) {
            logger.warn("{}. This deployment is awaiting a key, so that is expected", message)
        } else {
            error(message)
        }
    }

    fun showModel(model: LlmService<*>): String {
        val roles = properties.llms.filter { it.value == model.name }.keys
        val maybeRoles = if (roles.isNotEmpty()) " - Roles: ${roles.joinToString(", ")}" else ""
        return "name: ${model.name}, provider: ${model.provider}$maybeRoles"
    }

    fun resolveLlmOptions(llmOptions: LlmOptions): LlmOptions {
        val criteria = llmOptions.criteria
        if (criteria !is ByRoleModelSelectionCriteria) {
            return llmOptions
        }
        val resolved = resolveRole(criteria.role, ModelSelectionContextHolder.get())
        return llmOptions
            .withDefaultsFrom(resolved.llmOptions)
            .copy(
                modelSelectionCriteria = ModelSelectionCriteria.preResolved(resolved.llmService),
                // Keep the role that was asked for. Selection no longer consults it - the
                // pre-resolved criteria decide - but events, logs and cost attribution all want to
                // know a call was made "as cheapest", which is otherwise lost the moment it resolves.
                role = criteria.role,
            )
    }

    fun configuredOptionsForRole(role: String): LlmOptions? =
        family.roles.configuredFor(role, defaultLlm.provider)

    /**
     * Ask each resolver in turn what the role means, and materialize the answer.
     *
     * A role that cannot be satisfied throws, rather than quietly resolving to something else.
     * Falling back to the default LLM would mean a role like "cheapest" silently becoming the most
     * capable - and most expensive - model in the deployment, which is exactly the kind of thing
     * nobody notices until the bill arrives.
     *
     * Booting is a separate question: an unsatisfiable role only warns at startup, so a deployment
     * keyed for one provider still starts and still serves every role that does work.
     */
    private fun resolveRole(role: String, context: ModelSelectionContext): ResolvedRole {
        val attempt = attemptRole(role, context)
        val resolved = attempt.resolved
        if (resolved != null) {
            return resolved
        }
        val resolution = attempt.resolution
        if (setupRequired) {
            // No key has arrived yet, so no role can name a registered model and this is not a
            // misconfiguration. Hand back the placeholder rather than throwing: the caller then
            // fails with the same actionable "no LLM configured" error that the default LLM already
            // gives, instead of a NoSuitableModelException listing the placeholder as a choice.
            //
            // Not a silent substitution of the kind this method otherwise refuses. The objection to
            // falling back is that "cheapest" would quietly become a real, expensive model; the
            // placeholder answers nothing and bills nothing.
            //
            // The placeholder is told which role it is standing in for, so the eventual failure can
            // name the role and the model it wanted. That is the only thing separating a typo from
            // a key that has not arrived, and at this point it is the last chance to say it: both
            // look identical at startup, and after this the caller sees only "no LLM configured".
            val wantedModel = wantedModelFor(role, resolution)
            logger.debug(
                "Role '{}' (model '{}') has no registered model and this deployment is awaiting a key; using the placeholder",
                role, wantedModel ?: "unspecified",
            )
            val placeholder = (defaultLlm as? PlaceholderLlmService)?.forUnsatisfiedRole(role, wantedModel)
            return ResolvedRole(placeholder ?: defaultLlm, LlmOptions.withDefaults())
        }
        logger.warn(
            "No model available for role '{}' (provider: {})",
            role, context.provider ?: "deployment default",
        )
        throw NoSuitableModelException(ByRoleModelSelectionCriteria(role), llms.map { it.name })
    }

    /**
     * Ask each resolver in turn what the role means, and materialize whatever answers - or nothing,
     * if no resolver answered or the answer named a model this deployment cannot serve.
     *
     * Split out of [resolveRole] because the deployment default resolves through the same chain but
     * must not inherit its ending: an unsatisfiable role throws, where an unsatisfiable default
     * falls back to what `default-llm` resolved to at startup.
     *
     * Carries the raw [RoleResolution] back alongside the result so a caller can report what was
     * wanted, which is otherwise lost the moment materialization fails.
     */
    private fun attemptRole(role: String, context: ModelSelectionContext): RoleAttempt {
        val resolution = family.resolve(role, context)
        val resolved = family.materialize(role, resolution)
            ?.let { ResolvedRole(it.model, it.binding ?: LlmOptions.withDefaults()) }
        return RoleAttempt(resolution, resolved)
    }

    /**
     * What `default-llm` means for this call.
     *
     * A name that matched a registered service at startup is that service, resolved once and never
     * sent through the resolvers - so the ordinary deployment pays nothing for this and cannot have
     * its default overridden per call.
     *
     * Anything else goes through the role chain, on every call. That is the point: under BYOK the
     * first key arrives after startup, so a default fixed at construction can only be the
     * placeholder, permanently, until the process restarts. Resolving per call means the same key
     * that satisfies every other role satisfies the default too.
     *
     * A name that is neither a role nor a registered model reaches the chain as well, rather than
     * being refused for not looking like a role. Resolvers answer per call and per user; whether a
     * name is "a role" is not something this class can decide on their behalf.
     *
     * Falls back to [defaultLlm] - the placeholder, in the deployment this exists for - when nothing
     * answers, rather than throwing as an unsatisfied role does. A caller asking for the default
     * asked for whatever this deployment has, and the placeholder's "no LLM configured" is the
     * accurate answer; a [NoSuitableModelException] naming the default as an unsatisfiable role
     * would not be.
     */
    private fun defaultLlmService(): LlmService<*> {
        registeredDefaultLlm?.let { return it }
        val resolved = attemptRole(properties.defaultLlm, ModelSelectionContextHolder.get()).resolved?.llmService
        // A default naming a MODEL a runner has since started serving, for the same reason the
        // embedding default asks: the name was unresolvable at startup and is not any more. Guarded
        // on the name not being a ROLE, as the embedding default is: a role resolves through the
        // chain, which already includes the local resolvers, so asking the runners for a model
        // called `cheapest` can only miss - and a miss is an HTTP request, on the path of every
        // call a BYOK deployment makes before a key arrives.
            ?: properties.defaultLlm.takeUnless { properties.defaultLlmNamesRole() }?.let { family.locallyServed(it) }
        if (resolved == null) {
            // Debug, not warn: under BYOK this is the ordinary state of every call made before a
            // key arrives, and the eventual failure already names it.
            logger.debug(
                "Nothing resolved default LLM '{}' for this call; falling back to '{}'",
                properties.defaultLlm, defaultLlm.name,
            )
        }
        return resolved ?: defaultLlm
    }

    /**
     * A resolution attempt: what the resolvers said, and what it materialized into, if anything.
     */
    private data class RoleAttempt(
        val resolution: RoleResolution<LlmService<*>, LlmOptions>?,
        val resolved: ResolvedRole?,
    )

    /**
     * The model name [role] carried, taken from whatever answered for it.
     *
     * Read back off the resolution rather than re-queried, so it is the name resolution actually
     * tried and not a second, possibly different, lookup. A [RoleResolution.Credential] carries a
     * key rather than a model, so that one case does have to ask - the same call
     * [ModelFamily.materialize] made.
     *
     * Null when nothing named a model: no resolver answered at all, or the entry that did names
     * only a provider. There is then nothing to report beyond the role itself.
     */
    private fun wantedModelFor(role: String, resolution: RoleResolution<LlmService<*>, LlmOptions>?): String? =
        when (resolution) {
            is RoleResolution.Bound -> resolution.binding.modelName
            is RoleResolution.Credential ->
                family.roles.configuredFor(role, resolution.credential.provider)?.modelName
            // A Service resolved to a built service, so it never reaches the placeholder.
            is RoleResolution.Service, null -> null
        }

    /**
     * The chat service this deployment can serve under [name], registered or locally served.
     *
     * Registered first, always: a model captured at startup is the one the deployment was built
     * with, and a runner that happens to serve a model of the same name must not displace it.
     */
    private fun llmNamed(name: String): LlmService<*>? =
        family.registeredNamed(name) ?: family.locallyServed(name)

    /**
     * Every chat model name a caller could ask for, for the "available" list in a failure.
     *
     * The same set [ConfigurableModelProvider.listModelNames] reports, so being told what is available and then asking for
     * one of them cannot disagree.
     */
    fun listableLlmNames(): List<String> = family.listableNames()

    fun getLlm(criteria: ModelSelectionCriteria): LlmService<*> =
        when (criteria) {
            is ByRoleModelSelectionCriteria -> {
                resolveRole(criteria.role, ModelSelectionContextHolder.get()).llmService
            }

            is ByNameModelSelectionCriteria -> {
                llmNamed(criteria.name) ?: throw NoSuitableModelException(criteria, listableLlmNames())
            }

            is RandomByNameModelSelectionCriteria -> {
                val models = criteria.names.mapNotNull { llmNamed(it) }
                if (models.isEmpty()) {
                    throw NoSuitableModelException(criteria, listableLlmNames())
                }
                models.random()
            }

            is FallbackByNameModelSelectionCriteria -> {
                var llm: LlmService<*>? = null
                for (requestedName in criteria.names) {
                    llm = llmNamed(requestedName)
                    if (llm != null) {
                        break
                    } else {
                        logger.info("Requested LLM '{}' not found", requestedName)
                    }
                }
                llm
                    ?: throw NoSuitableModelException(criteria, listableLlmNames())
            }

            is AutoModelSelectionCriteria -> {
                // The infrastructure above this class should have resolved this
                error("Auto model selection criteria should have been resolved upstream")
            }

            is DefaultModelSelectionCriteria -> {
                defaultLlmService()
            }

            is PreResolvedModelSelectionCriteria<*> -> {
                @Suppress("UNCHECKED_CAST")
                criteria.resolved as LlmService<*>
            }
        }

    /**
     * A role, materialized: the service to call, and the options configured alongside it.
     */
    private data class ResolvedRole(
        val llmService: LlmService<*>,
        val llmOptions: LlmOptions,
    )
}
