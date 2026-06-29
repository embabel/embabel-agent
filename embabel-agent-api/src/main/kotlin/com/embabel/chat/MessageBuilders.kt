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
package com.embabel.chat

import com.embabel.common.ai.media.mimeTypeForDocumentExtension
import com.embabel.common.ai.media.mimeTypeForImageExtension
import java.io.File
import java.nio.file.Path

/**
 * Builder for creating multimodal UserMessages fluently
 */
class UserMessageBuilder {
    private val parts = mutableListOf<ContentPart>()
    private var name: String? = null

    fun text(text: String): UserMessageBuilder {
        parts.add(TextPart(text))
        return this
    }

    fun image(mimeType: String, data: ByteArray): UserMessageBuilder {
        parts.add(ImagePart(mimeType, data))
        return this
    }

    fun image(file: File): UserMessageBuilder {
        val mimeType = mimeTypeForImageExtension(file.extension)
        parts.add(ImagePart(mimeType, file.readBytes()))
        return this
    }

    fun image(path: Path): UserMessageBuilder = image(path.toFile())

    @JvmOverloads
    fun document(mimeType: String, data: ByteArray, filename: String? = null): UserMessageBuilder {
        parts.add(DocumentPart(mimeType, data, filename))
        return this
    }

    fun document(file: File): UserMessageBuilder {
        val mimeType = mimeTypeForDocumentExtension(file.extension)
        parts.add(DocumentPart(mimeType, file.readBytes(), file.name))
        return this
    }

    fun document(path: Path): UserMessageBuilder = document(path.toFile())

    fun name(name: String): UserMessageBuilder {
        this.name = name
        return this
    }

    fun build(): UserMessage = UserMessage(parts.toList(), name)
}

/**
 * Create a UserMessage builder
 */
fun userMessage(): UserMessageBuilder = UserMessageBuilder()
