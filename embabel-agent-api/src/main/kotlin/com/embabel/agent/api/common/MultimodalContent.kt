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

import com.embabel.chat.ContentPart
import com.embabel.chat.ImagePart
import com.embabel.chat.TextPart
import java.io.File
import java.nio.file.Path

/**
 * Represents multimodal content for Agent API operations.
 * This is a higher-level abstraction over the Chat API's ContentPart system
 * designed specifically for use in Agent actions and prompt runners.
 */
data class MultimodalContent(
    val text: String,
    val images: List<AgentImage> = emptyList()
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
         * Create multimodal content with text and multiple images
         */
        @JvmStatic
        fun withImages(text: String, images: List<AgentImage>): MultimodalContent =
            MultimodalContent(text, images)
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
            val mimeType = detectMimeType(file.extension)
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
            val mimeType = detectMimeType(extension)
            return AgentImage(mimeType, data)
        }

        private fun detectMimeType(extension: String): String {
            return when (extension.lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                else -> throw IllegalArgumentException(
                    "Unknown image format: .$extension. Supported formats: jpg, jpeg, png, gif, webp, bmp. " +
                    "For other formats, use AgentImage.create(mimeType, data) with an explicit MIME type."
                )
            }
        }
    }
}

/**
 * Builder for creating multimodal content fluently
 */
class MultimodalContentBuilder {
    private var text: String = ""
    private val images = mutableListOf<AgentImage>()

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

    fun build(): MultimodalContent = MultimodalContent(text, images.toList())
}

/**
 * Create a multimodal content builder
 */
fun multimodal(): MultimodalContentBuilder = MultimodalContentBuilder()
