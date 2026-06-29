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

import com.embabel.chat.ContentPart
import com.embabel.chat.DocumentPart
import com.embabel.chat.ImagePart
import com.embabel.chat.TextPart
import com.embabel.common.ai.media.isDocumentMimeType
import com.embabel.common.ai.media.isImageMimeType
import com.embabel.common.ai.media.mimeTypeForDocumentExtension
import com.embabel.common.ai.media.mimeTypeForImageExtension
import java.io.File
import java.nio.file.Path

/**
 * Represents multimodal content for Agent API operations.
 * This is a higher-level abstraction over the Chat API's ContentPart system
 * designed specifically for use in Agent actions and prompt runners.
 */
data class MultimodalContent(
    val text: String,
    val images: List<AgentImage> = emptyList(),
    val documents: List<AgentDocument> = emptyList(),
) {

    /**
     * Convert to Chat API ContentParts for internal processing
     */
    internal fun toContentParts(): List<ContentPart> {
        val parts = mutableListOf<ContentPart>()
        if (text.isNotEmpty()) {
            parts.add(TextPart(text))
        }
        images.forEach { image ->
            parts.add(ImagePart(image.mimeType, image.data))
        }
        documents.forEach { document ->
            parts.add(DocumentPart(document.mimeType, document.data, document.filename))
        }
        return parts
    }

    companion object {
        /**
         * Create text-only multimodal content
         */
        @JvmStatic
        fun fromText(content: String): MultimodalContent = MultimodalContent(content)

        /**
         * Create multimodal content with text and a single image
         */
        @JvmStatic
        fun withImage(text: String, image: AgentImage): MultimodalContent =
            MultimodalContent(text, listOf(image))

        /**
         * Create multimodal content with text and a single document
         */
        @JvmStatic
        fun withDocument(text: String, document: AgentDocument): MultimodalContent =
            MultimodalContent(text, documents = listOf(document))

        /**
         * Create multimodal content with text and multiple images
         */
        @JvmStatic
        fun withImages(text: String, images: List<AgentImage>): MultimodalContent =
            MultimodalContent(text, images)

        /**
         * Create multimodal content with text and multiple documents
         */
        @JvmStatic
        fun withDocuments(text: String, documents: List<AgentDocument>): MultimodalContent =
            MultimodalContent(text, documents = documents)
    }
}

/**
 * Represents an image for Agent API operations.
 * Provides convenient constructors for common image sources.
 */
data class AgentImage(
    val mimeType: String,
    val data: ByteArray
) {

    init {
        require(isImageMimeType(mimeType)) { "Invalid image MIME type: $mimeType" }
        require(data.isNotEmpty()) { "Image data cannot be empty" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AgentImage) return false
        return mimeType == other.mimeType && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        return mimeType.hashCode() * 31 + data.contentHashCode()
    }

    companion object {

        /**
         * Create an AgentImage from a file, auto-detecting MIME type
         */
        @JvmStatic
        fun fromFile(file: File): AgentImage {
            val mimeType = mimeTypeForImageExtension(file.extension)
            return AgentImage(mimeType, file.readBytes())
        }

        /**
         * Create an AgentImage from a Path, auto-detecting MIME type
         */
        @JvmStatic
        fun fromPath(path: Path): AgentImage = fromFile(path.toFile())

        /**
         * Create an AgentImage with explicit MIME type and data
         */
        @JvmStatic
        fun create(mimeType: String, data: ByteArray): AgentImage = AgentImage(mimeType, data)

        /**
         * Create an AgentImage from raw bytes, auto-detecting MIME type based on file extension
         */
        @JvmStatic
        fun fromBytes(filename: String, data: ByteArray): AgentImage {
            val extension = filename.substringAfterLast('.', "")
            val mimeType = mimeTypeForImageExtension(extension)
            return AgentImage(mimeType, data)
        }
    }
}

/**
 * Represents a document for Agent API operations.
 * Provides convenient constructors for common document sources.
 */
data class AgentDocument(
    val mimeType: String,
    val data: ByteArray,
    val filename: String? = null,
) {

    init {
        require(isDocumentMimeType(mimeType)) { "Invalid document MIME type: $mimeType" }
        require(data.isNotEmpty()) { "Document data cannot be empty" }
        require(filename == null || filename.isNotBlank()) { "Document filename cannot be blank" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AgentDocument) return false
        return mimeType == other.mimeType && filename == other.filename && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = mimeType.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + filename.hashCode()
        return result
    }

    companion object {

        /**
         * Create an AgentDocument from a file, auto-detecting MIME type
         */
        @JvmStatic
        fun fromFile(file: File): AgentDocument {
            val mimeType = mimeTypeForDocumentExtension(file.extension)
            return AgentDocument(mimeType, file.readBytes(), file.name)
        }

        /**
         * Create an AgentDocument from a Path, auto-detecting MIME type
         */
        @JvmStatic
        fun fromPath(path: Path): AgentDocument = fromFile(path.toFile())

        /**
         * Create an AgentDocument with explicit MIME type and data
         */
        @JvmStatic
        @JvmOverloads
        fun create(mimeType: String, data: ByteArray, filename: String? = null): AgentDocument =
            AgentDocument(mimeType, data, filename)

        /**
         * Create an AgentDocument from raw bytes, auto-detecting MIME type based on file extension
         */
        @JvmStatic
        fun fromBytes(filename: String, data: ByteArray): AgentDocument {
            val extension = filename.substringAfterLast('.', "")
            val mimeType = mimeTypeForDocumentExtension(extension)
            return AgentDocument(mimeType, data, filename)
        }
    }
}

/**
 * Builder for creating multimodal content fluently
 */
class MultimodalContentBuilder {
    private var text: String = ""
    private val images = mutableListOf<AgentImage>()
    private val documents = mutableListOf<AgentDocument>()

    fun text(content: String): MultimodalContentBuilder {
        this.text = content
        return this
    }

    fun image(image: AgentImage): MultimodalContentBuilder {
        this.images.add(image)
        return this
    }

    fun image(mimeType: String, data: ByteArray): MultimodalContentBuilder {
        this.images.add(AgentImage(mimeType, data))
        return this
    }

    fun image(file: File): MultimodalContentBuilder {
        this.images.add(AgentImage.fromFile(file))
        return this
    }

    fun image(path: Path): MultimodalContentBuilder {
        this.images.add(AgentImage.fromPath(path))
        return this
    }

    fun images(vararg images: AgentImage): MultimodalContentBuilder {
        this.images.addAll(images)
        return this
    }

    fun document(document: AgentDocument): MultimodalContentBuilder {
        this.documents.add(document)
        return this
    }

    @JvmOverloads
    fun document(mimeType: String, data: ByteArray, filename: String? = null): MultimodalContentBuilder {
        this.documents.add(AgentDocument(mimeType, data, filename))
        return this
    }

    fun document(file: File): MultimodalContentBuilder {
        this.documents.add(AgentDocument.fromFile(file))
        return this
    }

    fun document(path: Path): MultimodalContentBuilder {
        this.documents.add(AgentDocument.fromPath(path))
        return this
    }

    fun documents(vararg documents: AgentDocument): MultimodalContentBuilder {
        this.documents.addAll(documents)
        return this
    }

    fun build(): MultimodalContent = MultimodalContent(text, images.toList(), documents.toList())
}

/**
 * Create a multimodal content builder
 */
fun multimodal(): MultimodalContentBuilder = MultimodalContentBuilder()
