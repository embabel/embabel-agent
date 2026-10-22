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
 * Something a model can do, such as chat, embed text, classify or decide.
 *
 * Every capability interface extends this one. A model that can do several things implements
 * several capability interfaces, so the set of interfaces a model implements is the list of
 * what it can do. To add a new kind of model, such as image generation, add a new interface
 * that extends this one. Do not add a value to [ModelType].
 *
 * The capability interfaces today are [EmbeddingService],
 * [com.embabel.common.ai.classification.ClassificationService],
 * [com.embabel.common.ai.decision.DecisionService] and `com.embabel.agent.spi.LlmService`.
 *
 * Example of a new kind of model, and of checking what a service can do:
 * ```kotlin
 * interface ImageGenerationService : ModelCapability {
 *     fun generate(prompt: String): ByteArray
 * }
 *
 * // True for an embedding service, false for an image generation service.
 * fun canEmbed(service: ModelCapability): Boolean = service is EmbeddingService
 * ```
 *
 * See https://github.com/embabel/embabel-agent/issues/1585.
 */
@ApiStatus.Experimental
interface ModelCapability
