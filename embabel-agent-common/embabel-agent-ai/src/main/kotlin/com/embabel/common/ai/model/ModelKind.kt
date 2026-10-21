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
 * One kind of model the platform selects, such as chat or embedding, and the type of what a role
 * names for it.
 *
 * Open, not an enum: a new kind of model declares its own instance next to its capability
 * interface, and nothing here has to change. Resolvers, credential factories and local model
 * runners say which kind they serve by holding one, and the platform matches them by identity, so
 * declare each kind once as a constant and refer to that.
 *
 * @param C the capability a model of this kind has
 * @param B what a role binds a model of this kind to: [LlmOptions] for chat, [ModelName] for an
 * embedding model
 * @param name the kind's name in messages and logs, such as `chat`
 * @param capability the capability interface
 */
@ApiStatus.Experimental
open class ModelKind<C : ModelCapability, B : RoleBinding>(
    val name: String,
    val capability: Class<C>,
) {
    override fun toString(): String = "ModelKind($name)"
}
