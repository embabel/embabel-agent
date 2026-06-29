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

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MimeTypeUtilsTest {

    @Test
    fun `classifies supported image MIME types`() {
        assertThat(classifyMimeType(MimeTypes.IMAGE_PNG)).isEqualTo(MediaKind.IMAGE)
        assertThat(classifyMimeType(MimeTypes.IMAGE_JPEG)).isEqualTo(MediaKind.IMAGE)
        assertThat(isImageMimeType(MimeTypes.IMAGE_WEBP)).isTrue()
        assertThat(isDocumentMimeType(MimeTypes.IMAGE_WEBP)).isFalse()
    }

    @Test
    fun `classifies supported document MIME types`() {
        assertThat(classifyMimeType(MimeTypes.PDF)).isEqualTo(MediaKind.DOCUMENT)
        assertThat(classifyMimeType(MimeTypes.XLSX)).isEqualTo(MediaKind.DOCUMENT)
        assertThat(classifyMimeType(MimeTypes.ODS)).isEqualTo(MediaKind.DOCUMENT)
        assertThat(isDocumentMimeType(MimeTypes.ODT)).isTrue()
        assertThat(isImageMimeType(MimeTypes.ODT)).isFalse()
    }

    @Test
    fun `returns null for unsupported MIME type`() {
        assertThat(classifyMimeType("application/octet-stream")).isNull()
    }

    @Test
    fun `resolves image MIME type from extension case insensitively`() {
        assertThat(mimeTypeForImageExtension("jpg")).isEqualTo(MimeTypes.IMAGE_JPEG)
        assertThat(mimeTypeForImageExtension("JPEG")).isEqualTo(MimeTypes.IMAGE_JPEG)
        assertThat(mimeTypeForImageExtension("png")).isEqualTo(MimeTypes.IMAGE_PNG)
    }

    @Test
    fun `resolves document MIME type from extension case insensitively`() {
        assertThat(mimeTypeForDocumentExtension("pdf")).isEqualTo(MimeTypes.PDF)
        assertThat(mimeTypeForDocumentExtension("XLSX")).isEqualTo(MimeTypes.XLSX)
        assertThat(mimeTypeForDocumentExtension("ods")).isEqualTo(MimeTypes.ODS)
    }

    @Test
    fun `rejects unsupported extensions`() {
        assertThatThrownBy { mimeTypeForImageExtension("svg") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unsupported image extension: svg")

        assertThatThrownBy { mimeTypeForDocumentExtension("zip") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unsupported document extension: zip")
    }

    @Test
    fun `rejects document extension used as image extension with helpful message`() {
        assertThatThrownBy { mimeTypeForImageExtension("pdf") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Extension 'pdf' is a document type; use document input APIs")
    }

    @Test
    fun `rejects image extension used as document extension with helpful message`() {
        assertThatThrownBy { mimeTypeForDocumentExtension("png") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Extension 'png' is an image type; use image input APIs")
    }
}
