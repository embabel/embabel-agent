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

/**
 * The three objects a local runner's autoconfiguration publishes, built over ONE catalog.
 *
 * A runner module has nothing to decide here - it supplies a [LocalModelSource] and the rest is the
 * same every time - so the wiring lives once rather than three times. That the two resolvers share
 * a catalog was previously a comment in each module saying they did; here it is the construction.
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

    val roleResolver: LocalModelRoleResolver = LocalModelRoleResolver(catalog, properties)

    val embeddingRoleResolver: LocalModelEmbeddingRoleResolver =
        LocalModelEmbeddingRoleResolver(catalog, properties)
}
