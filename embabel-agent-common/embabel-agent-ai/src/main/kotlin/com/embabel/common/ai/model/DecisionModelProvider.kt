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

import com.embabel.common.ai.decision.DecisionService
import org.jetbrains.annotations.ApiStatus

/**
 * Supplies decision services by model name for models that are not registered as beans, such as
 * the chat models a prompted decision service wraps.
 *
 * The [DecisionServiceRegistry] asks providers only for names no registered service holds. It asks
 * them in order and keeps the first service returned, so each name is built at most once.
 * Defaults and roles may name any model in [modelNames]. A provider model never becomes a family
 * default unless configuration names it.
 *
 * Implementations must be thread-safe.
 */
@ApiStatus.Experimental
interface DecisionModelProvider {

    /** Name of this provider, for diagnostics. */
    val provider: String

    /**
     * Returns the model names this provider can serve now. The registry checks defaults and roles
     * against this list when it is built.
     *
     * @return the model names, in a stable order
     */
    fun modelNames(): List<String>

    /**
     * Creates a decision service for a model.
     *
     * @param name the model name
     * @return the service, or null when this provider does not serve that model
     */
    fun decisionServiceNamed(name: String): DecisionService?
}
