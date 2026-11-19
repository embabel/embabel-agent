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
package com.embabel.agent.api.validation.action

import com.embabel.agent.core.ActionException
import com.embabel.agent.spi.validation.action.ActionValidationFailure

/**
 * Thrown when an action fails validation and the
 * [com.embabel.agent.spi.validation.action.ActionValidationErrorPolicy] returns
 * [com.embabel.agent.spi.validation.action.ActionValidationOutcome.Fail].
 *
 * Not retryable: the same input or output would fail validation again.
 */
class ActionValidationException(
    val failure: ActionValidationFailure,
) : ActionException.Permanent(
    "Action '${failure.actionName}' failed ${failure.phase.name.lowercase()} validation: " +
            failure.results.flatMap { it.errors }.joinToString("; ") { "${it.code}: ${it.message}" }
)
