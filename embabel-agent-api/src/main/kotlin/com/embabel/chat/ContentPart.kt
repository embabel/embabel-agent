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

import com.embabel.common.ai.media.MimeTypes
import com.embabel.common.ai.media.isDocumentMimeType
import com.embabel.common.ai.media.isImageMimeType
import tools.jackson.databind.annotation.JsonDeserialize

/**
 * Represents a part of a multimodal message.
 * This sealed interface ensures type safety and extensibility for future media types.
 */
@JsonDeserialize(using = ContentPartDeserializer::class)
sealed interface ContentPart

/**
 * A binary media part that can be sent to models supporting multimodal input.
 */
sealed interface MediaPart : ContentPart {
    val mimeType: String
    val data: ByteArray
}

/**
 * A part of a message containing text content.
 */
data class TextPart(val text: String) : ContentPart {
    init {
        require(text.isNotEmpty()) { "Text content cannot be empty" }
    }
}

/**
 * A part of a message containing image data.
 */
data class ImagePart(
    override val mimeType: String,
    override val data: ByteArray
) : MediaPart {

    init {
        require(isImageMimeType(mimeType)) {
            "Invalid image MIME type: $mimeType. Supported: ${SUPPORTED_MIME_TYPES.joinToString()}"
        }
        require(data.isNotEmpty()) { "Image data cannot be empty" }
        require(data.size <= MAX_IMAGE_SIZE) {
            "Image too large: ${data.size} bytes. Maximum allowed: $MAX_IMAGE_SIZE bytes"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImagePart) return false
        return mimeType == other.mimeType && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        return mimeType.hashCode() * 31 + data.contentHashCode()
    }

    companion object {
        const val MAX_IMAGE_SIZE = 20 * 1024 * 1024 // 20MB

        val SUPPORTED_MIME_TYPES = MimeTypes.IMAGE_MIME_TYPES
    }
}

/**
 * A part of a message containing document data.
 */
data class DocumentPart(
    override val mimeType: String,
    override val data: ByteArray,
    val filename: String? = null
) : MediaPart {

    init {
        require(isDocumentMimeType(mimeType)) {
            "Invalid document MIME type: $mimeType. Supported: ${SUPPORTED_MIME_TYPES.joinToString()}"
        }
        require(data.isNotEmpty()) { "Document data cannot be empty" }
        require(filename == null || filename.isNotBlank()) { "Document filename cannot be blank" }
        require(data.size <= MAX_DOCUMENT_SIZE) {
            "Document too large: ${data.size} bytes. Maximum allowed: $MAX_DOCUMENT_SIZE bytes"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DocumentPart) return false
        return mimeType == other.mimeType && filename == other.filename && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = mimeType.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + filename.hashCode()
        return result
    }

    companion object {
        const val MAX_DOCUMENT_SIZE = 20 * 1024 * 1024 // 20MB

        val SUPPORTED_MIME_TYPES = MimeTypes.DOCUMENT_MIME_TYPES
    }
}
