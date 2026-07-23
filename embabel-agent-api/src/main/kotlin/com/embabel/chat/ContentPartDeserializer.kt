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

import com.embabel.common.ai.media.MediaKind
import com.embabel.common.ai.media.classifyMimeType
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode

/**
 * Deserializes [ContentPart] values without requiring synthetic type metadata.
 *
 * Text parts are identified by the `text` field. Binary media parts are identified by
 * `mimeType` and `data`, then routed to the appropriate [MediaPart] subtype using MIME
 * classification.
 */
class ContentPartDeserializer : JsonDeserializer<ContentPart>() {

    override fun deserialize(parser: JsonParser, context: DeserializationContext): ContentPart {
        val node = parser.codec.readTree<JsonNode>(parser)

        node[TEXT_FIELD]?.asText()?.let { text ->
            return TextPart(text)
        }

        val mimeType = node[MIME_TYPE_FIELD]?.asText()
            ?: throw JsonMappingException.from(parser, "Content part must contain '$TEXT_FIELD' or '$MIME_TYPE_FIELD'")
        val dataNode = node[DATA_FIELD]
            ?: throw JsonMappingException.from(parser, "Media content part must contain '$DATA_FIELD'")
        val data = dataNode.binaryValue()

        return when (classifyMimeType(mimeType)) {
            MediaKind.IMAGE -> ImagePart(mimeType, data)
            MediaKind.DOCUMENT -> DocumentPart(mimeType, data, node[FILENAME_FIELD]?.takeUnless { it.isNull }?.asText())
            null -> throw JsonMappingException.from(parser, "Unsupported media MIME type: $mimeType")
        }
    }

    companion object {
        private const val TEXT_FIELD = "text"
        private const val MIME_TYPE_FIELD = "mimeType"
        private const val DATA_FIELD = "data"
        private const val FILENAME_FIELD = "filename"
    }
}
