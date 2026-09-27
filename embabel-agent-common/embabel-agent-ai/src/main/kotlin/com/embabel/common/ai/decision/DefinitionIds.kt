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
package com.embabel.common.ai.decision

import tools.jackson.core.JsonEncoding
import tools.jackson.core.JsonGenerator
import tools.jackson.core.ObjectWriteContext
import tools.jackson.core.json.JsonFactory
import tools.jackson.core.json.JsonWriteFeature
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Computes the stable ids that name question definitions and decision specs on the wire.
 *
 * An id is a version prefix followed by the SHA-256 digest of a canonical JSON text, encoded
 * as unpadded base64url (RFC 4648 section 5), so every id is the prefix plus 43 characters.
 * Anyone who writes decision JSON outside this library must produce the same canonical bytes
 * to get the same id. The full recipe follows.
 *
 * A question id has the prefix `d1-`. Its canonical text is the JSON array
 * `[kind, name, instructions, [[id, description], ...]]`. The kind is the lower-case wire name:
 * `proposition`, `choice` or `rating`. The last element lists the choice options or rating
 * levels as `[id, description]` pairs in declared order. For a proposition it is `[]`.
 *
 * A spec id has the prefix `s1-`. Its canonical text is the JSON array of the spec's question
 * ids, as strings, in question order.
 *
 * The canonical text is compact JSON, as Jackson 3's default generator writes it:
 * - There is no whitespace between tokens.
 * - Strings are written as given, with no trimming and no Unicode normalization.
 * - `"` and `\` are written as `\"` and `\\`.
 * - Backspace, tab, line feed, form feed and carriage return are written as `\b`, `\t`, `\n`,
 *   `\f` and `\r`.
 * - Every other character below U+0020 is written as a Unicode escape: a backslash, the letter
 *   `u` and four hex digits with upper-case letters. U+001F becomes a backslash followed by `u001F`.
 * - An unpaired surrogate is written as the same kind of Unicode escape. U+D800 becomes a
 *   backslash followed by `uD800`.
 * - Everything else is written unescaped. That includes `/`, U+007F, U+2028 and all non-ASCII text.
 *
 * The text is encoded as UTF-8, with a supplementary character written as one four-byte
 * sequence, and the digest is taken over those bytes.
 *
 * For example, a proposition named `is_urgent` that asks `The customer needs a reply today.` has
 * the canonical text `["proposition","is_urgent","The customer needs a reply today.",[]]` and the
 * id `d1-81hzfR8Ooim5eJZok_AQMmXVY5l_p2bImz3be95T9z8`.
 *
 * The number after the prefix letter is the algorithm version. Any change to this recipe needs a
 * new prefix.
 */
internal object DefinitionIds {

    private const val QUESTION_PREFIX = "d1-"
    private const val SPEC_PREFIX = "s1-"
    private const val PROPOSITION = "proposition"
    private val kinds = setOf(PROPOSITION, "choice", "rating")

    // These settings equal the Jackson 3 defaults today. They are spelled out so that a change
    // of default in a later Jackson release cannot silently change every id.
    private val factory: JsonFactory = JsonFactory.builder()
        .enable(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
        .enable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
        .disable(JsonWriteFeature.ESCAPE_FORWARD_SLASHES)
        .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
        .build()

    private val base64Url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * Returns the `d1-` id of a question definition.
     *
     * @param kind the lower-case wire name of the question kind
     * @param name the question name
     * @param instructions the question instructions
     * @param entries the options or levels as `(id, description)` pairs in declared order, empty for a proposition
     * @throws IllegalArgumentException if the kind is unknown or a proposition has entries
     */
    fun question(kind: String, name: String, instructions: String, entries: List<Pair<String, String>>): String {
        require(kind in kinds) { "Unknown question kind '$kind'. Expected one of $kinds." }
        require(kind != PROPOSITION || entries.isEmpty()) { "A proposition has no options or levels." }
        return QUESTION_PREFIX + digest { generator ->
            generator.writeStartArray()
            generator.writeString(kind)
            generator.writeString(name)
            generator.writeString(instructions)
            generator.writeStartArray()
            for ((id, description) in entries) {
                generator.writeStartArray()
                generator.writeString(id)
                generator.writeString(description)
                generator.writeEndArray()
            }
            generator.writeEndArray()
            generator.writeEndArray()
        }
    }

    /**
     * Returns the `s1-` id of a spec made of the given questions.
     *
     * @param questionIds the `d1-` ids of the spec's questions in question order
     */
    fun spec(questionIds: List<String>): String = SPEC_PREFIX + digest { generator ->
        generator.writeStartArray()
        for (id in questionIds) generator.writeString(id)
        generator.writeEndArray()
    }

    private inline fun digest(write: (JsonGenerator) -> Unit): String {
        val bytes = ByteArrayOutputStream()
        factory.createGenerator(ObjectWriteContext.empty(), bytes, JsonEncoding.UTF8).use { write(it) }
        return base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
    }
}
