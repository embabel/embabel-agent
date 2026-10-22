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
package com.embabel.agent.config.models.byok

import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.ModelKind
import com.embabel.common.ai.model.ModelPlatform
import org.springframework.context.ApplicationContext

/** The model platforms in [this] context that build [kind], by bean name. */
private fun ApplicationContext.modelPlatformsBuilding(kind: ModelKind<*, *>): Map<String, ModelPlatform> =
    // Kinds are compared by identity, as the platform compares them.
    getBeansOfType(ModelPlatform::class.java).filterValues { platform -> platform.kinds.any { it === kind } }

/** The model platforms in [this] context that build chat models, by bean name. */
internal fun ApplicationContext.chatModelPlatforms(): Map<String, ModelPlatform> =
    modelPlatformsBuilding(LlmService.KIND)

/** The model platforms in [this] context that build embedding models, by bean name. */
internal fun ApplicationContext.embeddingModelPlatforms(): Map<String, ModelPlatform> =
    modelPlatformsBuilding(EmbeddingService.KIND)
