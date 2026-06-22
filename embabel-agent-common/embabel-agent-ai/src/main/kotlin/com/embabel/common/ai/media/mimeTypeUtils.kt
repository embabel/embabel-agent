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
fun mimeTypeForImageExtension(extension: String): String =
    when (extension.lowercase()) {
        "jpg", "jpeg" -> MimeTypes.IMAGE_JPEG
        "png" -> MimeTypes.IMAGE_PNG
        "gif" -> MimeTypes.IMAGE_GIF
        "webp" -> MimeTypes.IMAGE_WEBP
        "bmp" -> MimeTypes.IMAGE_BMP
        else -> throw IllegalArgumentException("Unsupported image extension: $extension")
    }

/**
 * Resolve a document file extension to a supported MIME type.
 */
fun mimeTypeForDocumentExtension(extension: String): String =
    when (extension.lowercase()) {
        "pdf" -> MimeTypes.PDF
        "xlsx" -> MimeTypes.XLSX
        "csv" -> MimeTypes.CSV
        "doc" -> MimeTypes.DOC
        "docx" -> MimeTypes.DOCX
        "odt" -> MimeTypes.ODT
        "ods" -> MimeTypes.ODS
        "odp" -> MimeTypes.ODP
        else -> throw IllegalArgumentException("Unsupported document extension: $extension")
    }
