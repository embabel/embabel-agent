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

enum class MediaKind {
    IMAGE,
    DOCUMENT,
}

class MimeTypes private constructor() {

    companion object {

        const val IMAGE_PNG = "image/png"
        const val IMAGE_JPEG = "image/jpeg"
        const val IMAGE_JPG = "image/jpg"
        const val IMAGE_GIF = "image/gif"
        const val IMAGE_WEBP = "image/webp"
        const val IMAGE_BMP = "image/bmp"

        const val PDF = "application/pdf"
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val CSV = "text/csv"
        const val DOC = "application/msword"
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val ODT = "application/vnd.oasis.opendocument.text"
        const val ODS = "application/vnd.oasis.opendocument.spreadsheet"
        const val ODP = "application/vnd.oasis.opendocument.presentation"

        val IMAGE_MIME_TYPES = setOf(
            IMAGE_PNG,
            IMAGE_JPEG,
            IMAGE_JPG,
            IMAGE_GIF,
            IMAGE_WEBP,
            IMAGE_BMP,
        )

        val DOCUMENT_MIME_TYPES = setOf(
            PDF,
            XLSX,
            CSV,
            DOC,
            DOCX,
            ODT,
            ODS,
            ODP,
        )
    }
}
