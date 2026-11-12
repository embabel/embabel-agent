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
 * Models a [ModelRegistry] creates on demand, by name, instead of having them registered up front.
 *
 * Every model a source returns is an instance of [type]. The registry uses that to decide which
 * families the source's models can serve, without creating them.
 *
 * @param M the kind of model the registry holds
 */
@ApiStatus.Experimental
interface ModelSource<out M : ModelCapability> {

    /** The provider name, for diagnostic messages. */
    val provider: String

    /** The type every model from this source has. */
    val type: Class<out M>

    /**
     * Lists the model names this source serves. The registry reads this only for validation and
     * diagnostic messages.
     *
     * @return the model names, in the order to list them
     */
    fun modelNames(): List<String>

    /**
     * Creates the model with the given name. The registry calls this at most once per name that
     * returns a model, and keeps the result.
     *
     * @param name the model name
     * @return the model, or null when this source does not serve that name
     */
    fun modelNamed(name: String): M?
}
