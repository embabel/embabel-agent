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
 * This is a class, not an enum, so a new kind of model can be added without changing this file:
 * declare one instance next to the capability interface. Resolvers, credential factories and local
 * model runners say which kind they serve by holding that instance, and the platform compares kinds
 * by identity (`===`), not by name. Declare each kind once as a constant and always refer to that
 * constant. The platform ships two: `LlmService.KIND` for chat and [EmbeddingService.KIND].
 *
 * The platform's model provider selects only those two kinds. A new kind, such as the one below,
 * also needs code that selects models of that kind. Until that exists, a resolver or credential
 * factory that holds the new kind is reported at startup and never asked.
 *
 * ```kotlin
 * interface RerankingService : ModelCapability {
 *     companion object {
 *         @JvmField
 *         val KIND = ModelKind<RerankingService, ModelName>("reranking", RerankingService::class.java)
 *     }
 * }
 * ```
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
