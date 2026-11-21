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
package com.embabel.common.ai.model.local

import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.ConfigurableModelProviderProperties
import com.embabel.common.ai.model.ConfigurableRoleResolver
import com.embabel.common.ai.model.ConfiguredRoles
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelCapability
import com.embabel.common.ai.model.ModelKind
import com.embabel.common.ai.model.ModelName
import com.embabel.common.ai.model.ModelSelectionContext
import com.embabel.common.ai.model.RoleBinding
import com.embabel.common.ai.model.RoleResolution
import com.embabel.common.ai.model.RoleResolver
import com.embabel.common.util.loggerFor
import org.springframework.core.Ordered

/**
 * Satisfies a role of one [ModelKind] with a model a local runner is serving RIGHT NOW.
 *
 * The local counterpart of the credential path, seam for seam: configuration names the model per
 * provider, the resolver is asked per call, and what it returns is a model built on the spot. The
 * difference is only where the endpoint comes from - a key for the credential path, a runner on this
 * machine for this one - and the asymmetry the platform had was that only one of them was asked
 * again after startup.
 *
 * Reads the same [ConfiguredRoles] that [ConfigurableRoleResolver] reads, so this resolver and
 * [ConfigurableRoleResolver] always agree on what a role means:
 *
 * ```yaml
 * embabel:
 *   models:
 *     roles:
 *       cheapest:
 *         docker: { model: ai/qwen3, temperature: 0.0 }
 * ```
 *
 * NAME THE MODEL UNDER THE PROVIDER COLUMN, not in the flat `embabel.models.llms` map, if it may
 * arrive after boot. The flat map is validated at startup and a name nothing has registered is
 * fatal there - correctly, since in a keyed deployment it is a typo. The nested map is not checked,
 * for the reason that makes it right here too: its entries name models this process may have no way
 * to serve yet.
 *
 * Declines rather than competing, in three cases. A role configuration says nothing about this
 * provider; a model the runner is not serving, so an absent runner simply answers nothing; and any
 * call with a user credential for some OTHER provider, because a user who brought a key asked for
 * their provider and serving them a model off this machine would answer with the wrong one.
 *
 * @param kind the kind of model this resolver answers for
 * @param catalog what the runner is serving, shared by the resolvers of every kind
 * @param roles what configuration says this kind's roles mean
 */
class LocalModelRoleResolver<C : ModelCapability, B : RoleBinding>(
    override val kind: ModelKind<C, B>,
    private val catalog: LocalModelCatalog,
    private val roles: ConfiguredRoles<B>,
) : RoleResolver<C, B>, Ordered {

    private val logger = loggerFor<LocalModelRoleResolver<*, *>>()

    /**
     * Last, like the platform's own resolvers, so an application that has decided what a role means
     * keeps deciding it.
     *
     * [Ordered.LOWEST_PRECEDENCE] is as late as a bean in the stream can sort, and it is the same
     * value an application resolver that states no order gets - so this ties with those, and
     * registration order breaks the tie. An application whose resolver must win over a local model
     * should say so with `@Order`, which every value below `LOWEST_PRECEDENCE` does.
     */
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun resolve(role: String, context: ModelSelectionContext): RoleResolution<C, B>? {
        if (!servesThisCaller(context, catalog.provider)) {
            return null
        }
        val binding = roles.configuredFor(role, catalog.provider) ?: return null
        val model = binding.modelName ?: return null
        if (!catalog.serves(model)) {
            return null
        }
        val service = catalog.model(kind, model)
        if (service == null) {
            logger.warn(
                "{} is serving '{}' for {} role '{}', but nothing built a {} model for it",
                catalog.provider, model, kind.name, role, kind.name,
            )
            return null
        }
        // The role's binding travels with the model, so a temperature configured beside the model
        // name is not lost by virtue of the model being local.
        return RoleResolution.Service(service, binding)
    }

    override fun toString(): String = "LocalModelRoleResolver(${kind.name}, ${catalog.provider})"
}

/**
 * Whether a model on this machine is an admissible answer for this call.
 *
 * It is, unless the caller brought a key for a DIFFERENT provider. Then it is not: the user asked
 * for their provider and expects to be billed for it, and a local model would quietly answer with
 * something else.
 */
private fun servesThisCaller(context: ModelSelectionContext, provider: String): Boolean {
    val credentialProvider = context.credential?.provider ?: return true
    return credentialProvider.equals(provider, ignoreCase = true)
}

/**
 * The objects a local runner's autoconfiguration publishes, built over ONE catalog.
 *
 * A runner module has nothing to decide here - it supplies a [LocalModelSource] and the rest is the
 * same every time - so the wiring lives once rather than once per module. That the resolvers share
 * a catalog is the construction, not a comment.
 *
 * Each module still declares its own `@Bean` methods returning these fields, because bean names
 * must differ across modules and a `@Configuration(proxyBeanMethods = false)` class calling its own
 * bean method twice would build two catalogs.
 *
 * @param source the runner to ask
 * @param properties where roles are read from
 * @param discovery how stale a listing may be, and whether to ask at all
 */
class LocalModelBeans(
    source: LocalModelSource,
    properties: ConfigurableModelProviderProperties,
    discovery: LocalModelDiscoveryProperties,
) {

    /** Published so the platform can LIST what the runner is serving, not only resolve roles. */
    val catalog: LocalModelCatalog = LocalModelCatalog(source, discovery)

    /** Satisfies chat roles from the runner. */
    val chatRoleResolver: LocalModelRoleResolver<LlmService<*>, LlmOptions> =
        LocalModelRoleResolver(LlmService.KIND, catalog, properties.chatRoles())

    /** Satisfies embedding roles from the runner. */
    val embeddingRoleResolver: LocalModelRoleResolver<EmbeddingService, ModelName> =
        LocalModelRoleResolver(EmbeddingService.KIND, catalog, properties.embeddingRoleBindings())
}
