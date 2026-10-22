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
 * The steps every kind shares (resolvers, user keys, local runners) are done by [family]. This class
 * holds what chat does differently from embedding. An unsatisfied role returns the placeholder while
 * the deployment waits for a key, and throws otherwise. A role's [LlmOptions] are merged into the
 * options of the call.
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
     * The application's resolvers are asked first and the configuration-driven resolver last. An
     * application resolver can answer for any role and return null for the others, which are then
     * resolved from configuration.
     */
    val family: ModelFamily<LlmService<*>, LlmOptions> = ModelFamily(
        kind = LlmService.KIND,
        registered = llms,
        roles = properties.chatRoles(),
        // The lambda is called when a role is resolved, not when the resolver is built, so
        // `defaultLlm`, which is declared below this property, is set by the time it is read.
        resolvers = applicationResolvers +
            ConfigurableRoleResolver(LlmService.KIND, properties.chatRoles()) { defaultLlm.provider },
        modelPlatforms = modelPlatforms,
        catalogs = localModelCatalogs,
        credentialCacheSize = properties.credentialServiceCacheSize,
        rolesProperty = ROLES_PROPERTIES,
        isPlaceholder = { it is PlaceholderLlmService },
    )

    /**
     * The registered service whose name is `default-llm`, or null when `default-llm` names a role
     * or a model that is not registered.
     *
     * When this is null, [defaultLlmService] resolves the default through the role resolvers on
     * every call. When it is not null, the default is this service, found once at startup, and the
     * resolvers are not asked.
     */
    private val registeredDefaultLlm: LlmService<*>? = llms.firstOrNull { it.name == properties.defaultLlm }

    /**
     * The default chat model found at startup. Its provider is the deployment's provider for role
     * resolution, and it is returned for [DefaultModelSelectionCriteria] when no resolver satisfies
     * the default on a call.
     *
     * When `default-llm` is not the name of a registered model, it is looked up as a role: first in
     * the flat `llms` map, because that map has one model per role and needs no provider, then in
     * the nested `roles` map. When neither gives a registered model, the placeholder is used. When
     * there is no placeholder either, construction throws.
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
     * The registered service named by a nested `roles` entry for `default-llm`, taken from the first
     * provider whose model is registered.
     *
     * The deployment's provider cannot be used to choose the provider entry, because the
     * deployment's provider is read from the value computed here. A deployment can only serve the
     * providers it has registered models for, so this takes the first provider that has one. When
     * several have one, any of them is acceptable as the startup default. Each call still uses the
     * entry for the provider of the key that is active on that call.
     *
     * Deployments that hold their own key also use the nested map, to configure each role per
     * provider for users who will bring their own keys. Such a deployment may have no placeholder.
     * Without this lookup, it could not use a nested role as its default, and it would fail to
     * start.
     */
    private fun nestedRoleDefaultLlm(): LlmService<*>? =
        properties.roles[properties.defaultLlm]
            ?.values
            ?.mapNotNull { it.modelName }
            ?.firstNotNullOfOrNull { model -> llms.firstOrNull { it.name == model } }

    /**
     * The startup error message for a `default-llm` that resolved to nothing.
     *
     * A configured role that no registered model satisfies gets a different message from a name
     * that is neither a registered model nor a configured role. Each message names the setting
     * the reader has to change.
     */
    private fun unresolvableDefaultLlmMessage(): String =
        if (properties.defaultLlmNamesRole())
            "Default LLM '${properties.defaultLlm}' is a configured role, but nothing this deployment has registered satisfies it. Point the role at one of the available models: ${llms.map { it.name }}, or set 'embabel.models.default-llm' to one of them directly."
        else
            "Default LLM '${properties.defaultLlm}' is neither a registered model nor a configured role. Set the 'embabel.models.default-llm' property to one of the available models: ${llms.map { it.name }}, or to one of the roles: ${(properties.llms.keys + properties.roles.keys).toList()}."

    /**
     * Whether this deployment is waiting for a key rather than misconfigured.
     *
     * True when [defaultLlm] is a [PlaceholderLlmService]. That happens when `default-llm` names
     * the placeholder, or when it names a model or role that gave no registered model and the
     * placeholder was used instead. This means keys arrive at runtime. Only in this mode is a
     * configured model name that is not registered treated as expected rather than as a typo.
     *
     * A deployment that has a key resolves `default-llm` to a registered model that is not the
     * placeholder, so it is not in this mode, even when a placeholder is also registered. A
     * placeholder is also registered when a BYOK starter and a provider starter are both on the
     * classpath.
     */
    val setupRequired: Boolean = defaultLlm is PlaceholderLlmService

    /**
     * The registered placeholder, if this deployment carries one.
     *
     * Found by the [PlaceholderLlmService] type rather than by name, because this class must not
     * depend on the BYOK module that implements it.
     */
    private fun placeholderLlm(): LlmService<*>? =
        family.placeholder()
            ?.also {
                // A model name that is not registered is logged as a warning that names it.
                // Without the warning, a deployment whose key is set but whose model failed to
                // register would switch to the placeholder with no message.
                //
                // A role is logged at info instead. The role has not failed: it is resolved on each
                // call, and the placeholder is used only on a call that nothing satisfies. A warning
                // here would appear on every startup of a deployment that waits for keys, which is
                // the kind of deployment the placeholder is registered for.
                if (properties.defaultLlmNamesRole()) {
                    logger.info(
                        """
                        Default LLM '{}' is a role and is resolved on each call.
                        Until a key is supplied, each call gets the '{}' placeholder
                        """.trimIndent(),
                        properties.defaultLlm, it.name,
                    )
                } else {
                    logger.warn(
                        """
                        Default LLM '{}' is not registered, so the '{}' placeholder is used instead.
                        Each call fails with a 'No LLM is configured' error until a model with that name is registered or a local runner serves it. Available: {}
                        """.trimIndent(),
                        properties.defaultLlm, it.name, llms.map { it.name },
                    )
                }
            }

    /**
     * Throws on a flat `llms` entry or a nested `roles` entry that this deployment cannot satisfy.
     * When the deployment is waiting for a key ([setupRequired]), the entry is expected and only a
     * warning is logged.
     */
    fun validate() {
        properties.llms.forEach { (role, model) ->
            if (llms.none { it.name == model }) {
                // Throw, unless this deployment is waiting for a key. In a deployment that has a
                // key, a name that matches no registered model is a typo. If startup continued, the
                // error would appear later, on whichever call first asks for that role. In
                // setup-required mode the deployment is waiting for the key its models need, so the
                // missing name is expected and only a warning is logged.
                if (setupRequired) {
                    logger.warn(
                        """
                        LLM '{}' for role '{}' is not registered. This deployment is awaiting a key, so that is expected;
                        the role will report 'No LLM is configured' until one is supplied. Available: {}
                        """.trimIndent(),
                        model, role, llms.map { it.name },
                    )
                } else {
                    error("LLM '$model' for role '$role' is not registered. Available: ${llms.map { it.name }}")
                }
            }
        }
        checkNestedRoles()
    }

    /**
     * Checks `embabel.models.roles` entries that cannot be satisfied with the same rule [validate]
     * uses for the flat map: throw, or log a warning when [setupRequired].
     *
     * Only entries for the deployment's own provider are checked. An entry for another provider is
     * used when a user brings a key for that provider, so its model is not expected to be
     * registered here. Warnings about those entries would always appear, and operators would learn
     * to ignore them.
     *
     * Entries for a provider in [lateArrivingProviders], one that declared [LateArrivingModels], are
     * not checked either and are only logged at info. That provider's models are pulled on the host
     * after startup, so before setup a role can name a model that has not been pulled yet. Stopping
     * startup would prevent the operator from reaching the point where they can pull it.
     *
     * Without this check, a typo under `roles` is not reported until something asks for the role.
     * Then the nested entry is found, its model is not registered, and resolution throws. It does
     * not fall back to the flat `llms` map, so a typo in a nested entry makes the role fail even when
     * the flat map has a working entry for it.
     */
    private fun checkNestedRoles() {
        val deploymentProvider = defaultLlm.provider
        properties.roles.forEach { (role, byProvider) ->
            byProvider
                .filterKeys { it.equals(deploymentProvider, ignoreCase = true) }
                .forEach { (provider, options) ->
                    val model = options.modelName
                    if (provider.lowercase() in lateArrivingProviders) {
                        // Log the skipped entry at info. It is skipped by a check that would
                        // otherwise stop startup, and an operator reading the startup log to find
                        // out why a role does nothing needs to see that.
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
                            LLM '$model' for role '$role' under provider '$provider', which is this deployment's own provider, is not registered. Available: ${llms.map { it.name }}
                            """.trimIndent(),
                        )
                    }
                }
        }
    }

    /**
     * Reports a nested role this deployment cannot satisfy: throws in a deployment that holds a
     * key, and logs a warning in one that is waiting for a key.
     *
     * The flat check in [validate] applies the same rule, so a typo under `roles` stops startup just
     * as the same typo under `llms` does.
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
                // Keep the role that was asked for. Model selection does not read it, because the
                // pre-resolved criteria already name the service. Events, logs and cost attribution
                // read it to know that a call was made for a role such as "cheapest". Without it
                // that would be lost once the role is resolved.
                role = criteria.role,
            )
    }

    fun configuredOptionsForRole(role: String): LlmOptions? =
        family.roles.configuredFor(role, defaultLlm.provider)

    /**
     * Ask each resolver in turn what the role means, and materialize the answer.
     *
     * A role that cannot be satisfied throws [NoSuitableModelException]. The exception is a
     * deployment waiting for a key, which gets the placeholder (see the comments in the body). The
     * role does not fall back to the default LLM. If it did, a role such as "cheapest" would use the
     * default model, which may be the most capable and most expensive one, and nothing would report
     * it.
     *
     * Startup is checked separately, by [validate]. That check skips nested entries for providers
     * other than the deployment's own, so a deployment that holds a key for one provider still
     * starts and serves every role that has a model.
     */
    private fun resolveRole(role: String, context: ModelSelectionContext): ResolvedRole {
        val attempt = attemptRole(role, context)
        val resolved = attempt.resolved
        if (resolved != null) {
            return resolved
        }
        val resolution = attempt.resolution
        if (setupRequired) {
            // No key has arrived yet, so a role cannot resolve to a registered model, and that is
            // not a configuration error. Return the placeholder instead of throwing. The caller
            // then fails with a "no LLM configured" error, as it does for the default LLM, instead
            // of a NoSuitableModelException that lists the placeholder as an available model.
            //
            // This does not break the rule against falling back to the default LLM. That rule is
            // there so that "cheapest" does not use an expensive model without anyone knowing. The
            // placeholder does not answer any call and costs nothing.
            //
            // The placeholder is given the role and the model the role wanted, so its error can
            // name them. That is the only information that tells a typo apart from a key that has
            // not arrived. Both look the same at startup, and after this point the caller sees only
            // "no LLM configured".
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
     * Asks each resolver in turn what the role means, and materializes the answer. The result has
     * no model when no resolver answered or the answer named a model this deployment cannot serve.
     *
     * Separate from [resolveRole] because [defaultLlmService] uses the same resolvers but handles a
     * failure differently: an unsatisfied role throws, and an unsatisfied default returns
     * [defaultLlm], the model `default-llm` resolved to at startup.
     *
     * Also returns the [RoleResolution] the resolvers gave, so a caller can report which model was
     * wanted when materialization fails.
     */
    private fun attemptRole(role: String, context: ModelSelectionContext): RoleAttempt {
        val resolution = family.resolve(role, context)
        val resolved = family.materialize(role, resolution)
            // A model resolved without a binding carries no configured tuning, so it gets the
            // platform default options.
            ?.let { ResolvedRole(it.model, it.binding ?: LlmOptions.withDefaults()) }
        return RoleAttempt(resolution, resolved)
    }

    /**
     * What `default-llm` means for this call.
     *
     * When `default-llm` matched a registered service at startup, the default is that service. It
     * was found once, and the resolvers are not asked, so such a deployment does no work per call
     * for this and its default cannot be changed per call.
     *
     * Otherwise the default is resolved through the role resolvers on every call. Under BYOK the
     * first key arrives after startup, so a default chosen at construction would be the placeholder
     * until the process restarts. Resolved per call, the default uses the same key as every other
     * role.
     *
     * A name that is neither a configured role nor a registered model is also passed to the
     * resolvers. An application resolver may answer for any name, per call and per user, so this
     * class does not decide which names are roles.
     *
     * When nothing answers, this returns [defaultLlm] instead of throwing as an unsatisfied role
     * does. In a deployment waiting for a key, [defaultLlm] is the placeholder. A caller asking for
     * the default asked for whatever this deployment has, so the placeholder's "no LLM configured"
     * error is the accurate answer. A [NoSuitableModelException] would wrongly report the default
     * as a role that cannot be satisfied.
     */
    private fun defaultLlmService(): LlmService<*> {
        registeredDefaultLlm?.let { return it }
        val resolved = attemptRole(properties.defaultLlm, ModelSelectionContextHolder.get()).resolved?.llmService
        // When `default-llm` names a model, ask the local runners for it, as the embedding default
        // does. The model may not have been served at startup but may be served now. This is
        // skipped when `default-llm` names a role. A role is resolved by the resolvers above, which
        // include the local resolvers, so asking the runners for a model named after the role (for
        // example `cheapest`) would find nothing. Each such lookup is an HTTP request, and it would
        // be made on every call a BYOK deployment makes before a key arrives.
            ?: properties.defaultLlm.takeUnless { properties.defaultLlmNamesRole() }?.let { family.locallyServed(it) }
        if (resolved == null) {
            // Logged at debug, not warn. Under BYOK every call made before a key arrives reaches
            // this point, and the error the caller gets later already names the default.
            logger.debug(
                "Nothing resolved default LLM '{}' for this call; using '{}'",
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
     * Read from the resolution instead of asking the resolvers again, so it is the name that
     * resolution tried. A [RoleResolution.Credential] carries a key and no model name, so for it the
     * name is looked up in the role configuration, with the same call [ModelFamily.materialize]
     * makes.
     *
     * Null when no model was named: no resolver answered, or the configured entry has a provider
     * and no model name. Only the role is then reported.
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
     * Registered models are checked first. A model registered at startup is the one the deployment
     * was configured with, and a local runner serving a model with the same name must not replace
     * it.
     */
    private fun llmNamed(name: String): LlmService<*>? =
        family.registeredNamed(name) ?: family.locallyServed(name)

    /**
     * Every chat model name a caller could ask for, for the "available" list in a failure.
     *
     * [ConfigurableModelProvider.listModelNames] reports the same set, so every name it lists can be
     * asked for.
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

    companion object {

        /** The properties a chat role's model is looked up in, named in log messages. */
        const val ROLES_PROPERTIES = "embabel.models.roles or embabel.models.llms"
    }
}
