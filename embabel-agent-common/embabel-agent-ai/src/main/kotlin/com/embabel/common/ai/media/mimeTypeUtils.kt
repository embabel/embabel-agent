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
package com.embabel.common.ai.media

import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.embabel.common.ai.media.mimeTypeUtils")

/**
 * Classify a supported MIME type into the media category Embabel uses internally.
 */
fun classifyMimeType(mimeType: String): MediaKind? =
    when (mimeType.lowercase()) {
        in MimeTypes.IMAGE_MIME_TYPES -> MediaKind.IMAGE
        in MimeTypes.DOCUMENT_MIME_TYPES -> MediaKind.DOCUMENT
        else -> null
    }

/**
 * Return true if the MIME type is one of the supported image input types.
 */
fun isImageMimeType(mimeType: String): Boolean =
    classifyMimeType(mimeType) == MediaKind.IMAGE

/**
 * Return true if the MIME type is one of the supported document input types.
 */
fun isDocumentMimeType(mimeType: String): Boolean =
    classifyMimeType(mimeType) == MediaKind.DOCUMENT

/**
 * Resolve an image file extension to a supported MIME type.
 */
fun mimeTypeForImageExtension(extension: String): String {
    val normalizedExtension = extension.lowercase()
    return when {
        normalizedExtension in MimeTypes.IMAGE_MIME_TYPES_BY_EXTENSION ->
            MimeTypes.IMAGE_MIME_TYPES_BY_EXTENSION.getValue(normalizedExtension)

        normalizedExtension in MimeTypes.DOCUMENT_MIME_TYPES_BY_EXTENSION -> throw IllegalArgumentException(
            "Extension '$extension' is a document type; use document input APIs"
        )

        else -> throw IllegalArgumentException("Unsupported image extension: $extension")
    }.also { mimeType ->
        logger.debug("Resolved image MIME type: extension={}, mimeType={}", normalizedExtension, mimeType)
    }
}

/**
 * Resolve a document file extension to a supported MIME type.
 */
fun mimeTypeForDocumentExtension(extension: String): String {
    val normalizedExtension = extension.lowercase()
    return when {
        normalizedExtension in MimeTypes.DOCUMENT_MIME_TYPES_BY_EXTENSION ->
            MimeTypes.DOCUMENT_MIME_TYPES_BY_EXTENSION.getValue(normalizedExtension)

        normalizedExtension in MimeTypes.IMAGE_MIME_TYPES_BY_EXTENSION -> throw IllegalArgumentException(
            "Extension '$extension' is an image type; use image input APIs"
        )

        else -> throw IllegalArgumentException("Unsupported document extension: $extension")
    }.also { mimeType ->
        logger.debug("Resolved document MIME type: extension={}, mimeType={}", normalizedExtension, mimeType)
    }
}
