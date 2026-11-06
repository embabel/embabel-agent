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
 * Thrown when a service selection cannot be satisfied. The message names the request, the services
 * and roles available to the family, the family default and the setting that fixes the selection.
 *
 * @param reason the kind of selection failure
 * @param message the diagnostic message
 */
@ApiStatus.Experimental
class ServiceSelectionException(
    val reason: Reason,
    message: String,
) : IllegalStateException(message) {

    /** The kind of selection failure. */
    enum class Reason {
        /** No service of the family is registered under the requested name. */
        UNKNOWN_NAME,

        /** The named service exists but belongs to a family that cannot answer the request. */
        WRONG_CAPABILITY,

        /** The requested role is not bound in the family. */
        UNKNOWN_ROLE,

        /** The family has no explicit default, no eligible default candidate and no eligible service. */
        NO_DEFAULT,

        /** The family has no explicit default and several eligible candidates or services. */
        AMBIGUOUS_DEFAULT,
    }
}
