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

import org.jetbrains.annotations.ApiStatus
import org.springframework.core.Ordered

/**
 * The platform's own [RoleResolver] for one [ModelKind], consulted after any the application
 * registers.
 *
 * Reads [ConfiguredRoles]: the nested role, then provider, then binding map, and the flat role to
 * binding map.
 *
 * The active provider is [ModelSelectionContext.provider] when a user key is in play, and
 * otherwise [defaultProviderName]. A single-provider deployment therefore reads the entries for its
 * own provider without setting a context at all.
 *
 * When the nested map has no entry for the role under the active provider, the resolver reads the
 * flat map instead. It does not do this under a user key, for the reason [resolve] gives.
 *
 * @param kind the kind of model this resolver answers for
 * @param roles what configuration says this kind's roles mean
 * @param defaultProviderName provider of the deployment's default model of this kind, used when no
 * key is active. A function because that default is resolved by [ConfigurableModelProvider] against
 * registered beans, after this is constructed.
 */
@ApiStatus.Experimental
class ConfigurableRoleResolver<C : ModelCapability, B : RoleBinding>(
    override val kind: ModelKind<C, B>,
    private val roles: ConfiguredRoles<B>,
    private val defaultProviderName: () -> String?,
) : RoleResolver<C, B>, Ordered {

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun resolve(role: String, context: ModelSelectionContext): RoleResolution<C, B>? {
        val activeProvider = context.provider ?: defaultProviderName()
        val configuredForProvider = roles.boundFor(role, activeProvider)
        if (configuredForProvider != null) {
            // A user key beats deployment credentials: hand the key back and let the platform
            // build a model for the one this role names under that provider.
            return context.credential
                ?.let { RoleResolution.Credential(it) }
                ?: RoleResolution.Bound(configuredForProvider)
        }
        if (context.credential != null) {
            // The flat map names models the deployment is keyed for. Serving them to a user who
            // brought their own key would quietly bill the deployment for a call the user meant to
            // pay for, so leave it alone and let the role fail for this user.
            return null
        }
        return roles.flatFor(role)?.let { RoleResolution.Bound(it) }
    }

    override fun toString(): String = "ConfigurableRoleResolver(${kind.name})"
}
