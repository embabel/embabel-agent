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
package com.embabel.agent.api.common

import com.embabel.chat.DocumentPart
import com.embabel.chat.UserMessage
import java.io.File
import java.nio.file.Path

/**
 * Convenience extension methods for PromptRunner document operations.
 *
 * Document shortcuts append user messages containing document parts. They do not
 * store documents in PromptRunner state, avoiding delegate plumbing while the
 * higher-level API settles.
 */

/**
 * Add a document as a user message.
 */
fun PromptRunner.withDocument(document: AgentDocument): PromptRunner =
    withMessage(UserMessage(parts = listOf(DocumentPart(document.mimeType, document.data, document.filename))))

/**
 * Add a document from a file as a user message.
 */
fun PromptRunner.withDocument(file: File): PromptRunner =
    withDocument(AgentDocument.fromFile(file))

/**
 * Add a document from a path as a user message.
 */
fun PromptRunner.withDocument(path: Path): PromptRunner =
    withDocument(AgentDocument.fromPath(path))

/**
 * Add a document with explicit MIME type and data as a user message.
 */
fun PromptRunner.withDocument(mimeType: String, data: ByteArray, filename: String? = null): PromptRunner =
    withDocument(AgentDocument.create(mimeType, data, filename))

/**
 * Create multimodal content with text and a single document.
 *
 * For document files or paths, use `multimodal(text).document(fileOrPath).build()`.
 * `multimodal(text, File)` and `multimodal(text, Path)` are image shortcuts.
 */
fun multimodal(text: String, document: AgentDocument): MultimodalContent =
    MultimodalContent.withDocument(text, document)
