/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.common

import java.io.File
import java.nio.file.Path

/**
 * Convenience extension methods for PromptRunner image operations.
 * These provide Kotlin-friendly shortcuts while the core API remains Java-friendly.
 */

/**
 * Add an image from a file
 */
fun PromptRunner.withImage(file: File): PromptRunner =
    withImage(AgentImage.fromFile(file))

/**
 * Add an image from a path
 */
fun PromptRunner.withImage(path: Path): PromptRunner =
    withImage(AgentImage.fromPath(path))

/**
 * Add an image with explicit MIME type and data
 */
fun PromptRunner.withImage(mimeType: String, data: ByteArray): PromptRunner =
    withImage(AgentImage.create(mimeType, data))

/**
 * Create multimodal content builder with initial text
 */
fun multimodal(text: String): MultimodalContentBuilder =
    MultimodalContentBuilder().text(text)

/**
 * Create multimodal content with text and a single image
 */
fun multimodal(text: String, image: AgentImage): MultimodalContent =
    MultimodalContent(text, listOf(image))

/**
 * Create multimodal content with text and image file
 */
fun multimodal(text: String, imageFile: File): MultimodalContent =
    multimodal(text, AgentImage.fromFile(imageFile))

/**
 * Create multimodal content with text and image path
 */
fun multimodal(text: String, imagePath: Path): MultimodalContent =
    multimodal(text, AgentImage.fromPath(imagePath))
