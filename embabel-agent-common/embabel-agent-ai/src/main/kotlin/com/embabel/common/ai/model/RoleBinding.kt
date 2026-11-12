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
 * What a role names under one provider: a model, and whatever is configured to travel with it.
 *
 * A chat role binds [LlmOptions], so a temperature configured beside the model name arrives with
 * it. A kind of model with nothing to configure beyond the name binds [ModelName].
 */
@ApiStatus.Experimental
interface RoleBinding {

    /** The model the role names, or null when the binding names none. */
    val modelName: String?
}

/**
 * A role binding that is only a model name.
 *
 * @param modelName the model the role names
 */
@ApiStatus.Experimental
data class ModelName(override val modelName: String) : RoleBinding
