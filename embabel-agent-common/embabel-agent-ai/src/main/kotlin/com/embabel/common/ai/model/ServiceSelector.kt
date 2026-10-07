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

/**
 * Selects a service of one family by registration name, by role, by the family default, or by a
 * supplied instance.
 *
 * @param S the family's service type
 */
@ApiStatus.Experimental
interface ServiceSelector<S : ModelMetadata> {

    /**
     * Returns the family default service.
     *
     * @return the default service
     * @throws ServiceSelectionException when no default is set and none can be derived
     */
    fun defaultService(): S

    /**
     * Returns the service registered under [name].
     *
     * @param name the registration name
     * @return the registered service
     * @throws ServiceSelectionException when no service of this family is registered under the name
     */
    fun named(name: String): S

    /**
     * Returns the service bound to [role] in this family.
     *
     * @param role the role name, scoped to this family
     * @return the bound service
     * @throws ServiceSelectionException when the role is not bound in this family
     */
    fun byRole(role: String): S

    /**
     * Returns [service] as the selection. The service is not registered.
     *
     * @param service a service built by the caller
     * @return the same service
     */
    fun using(service: S): S
}
