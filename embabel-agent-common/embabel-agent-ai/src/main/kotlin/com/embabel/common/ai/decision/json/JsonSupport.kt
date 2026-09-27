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
package com.embabel.common.ai.decision.json

import com.embabel.common.ai.classification.Category
import com.embabel.common.ai.decision.ExecutionMode
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.RatingLevel
import com.embabel.common.ai.decision.json.StrictObjectReader.MemberValue
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.exc.MismatchedInputException
import java.util.Locale

// The pieces SpecJson and ResponseJson both need: turning a validation failure into a Jackson
// mismatch, writing and reading the id/description entries behind options and levels, looking up
// a question kind by its wire name, and starting a nested object read whether it comes straight
// off the parser or from a member already captured as a MemberValue.

// Lower-case wire name for an enum whose constant name is already snake case, such as
// ExecutionMode, FailureReason and RatingStatistic. QuestionKind carries its own explicit
// wireName instead, since "PROPOSITION" is not its wire form.
internal val Enum<*>.snakeName: String get() = name.lowercase(Locale.ROOT)

internal val KINDS_BY_WIRE_NAME: Map<String, QuestionKind> = QuestionKind.entries.associateBy { it.wireName }
internal val MODES_BY_WIRE_NAME: Map<String, ExecutionMode> = ExecutionMode.entries.associateBy { it.snakeName }

internal val KIND_EXPECTED = "one of " + KINDS_BY_WIRE_NAME.keys.joinToString(", ")

internal val OPTION_READER = StrictObjectReader(Category::class.java, listOf("id", "description"))
internal val LEVEL_READER = StrictObjectReader(RatingLevel::class.java, listOf("id", "description"))

// Starts reading one object with the given reader and passes each member to the handler. It is
// either a top-level read on the parser or a nested read through a MemberValue already captured
// from an enclosing object.
internal typealias ObjectRead = (StrictObjectReader, (String, MemberValue) -> Unit) -> Unit

internal fun topLevel(parser: JsonParser, context: DeserializationContext): ObjectRead =
    { reader, handler -> reader.read(parser, context, handler) }

internal fun nested(value: MemberValue): ObjectRead =
    { reader, handler -> value.readObject(reader, handler) }

// Runs a public builder or factory and turns its validation failure into an input mismatch that
// keeps the message. The message is passed unformatted, so a '%' in it is harmless.
internal inline fun <T> construct(context: DeserializationContext, target: Class<*>, make: () -> T): T =
    try {
        make()
    } catch (e: IllegalArgumentException) {
        throw MismatchedInputException.from(context.parser, target, e.message ?: "Invalid ${target.simpleName}")
            .withCause(e)
    }

internal fun writeEntries(generator: JsonGenerator, member: String, entries: List<Pair<String, String>>) {
    generator.writeArrayPropertyStart(member)
    for ((id, description) in entries) {
        generator.writeStartObject()
        generator.writeStringProperty("id", id)
        generator.writeStringProperty("description", description)
        generator.writeEndObject()
    }
    generator.writeEndArray()
}

// Reads a JSON array of {id, description} entries and builds one value per entry. Both members
// are required, so the reader has already rejected an entry missing either by the time make runs.
internal fun <T> readEntries(
    context: DeserializationContext,
    value: MemberValue,
    reader: StrictObjectReader,
    make: (String, String) -> T,
): List<T> {
    val entries = ArrayList<T>()
    value.readArray { element ->
        var id: String? = null
        var description: String? = null
        element.readObject(reader) { member, entry ->
            when (member) {
                "id" -> id = entry.string()
                "description" -> description = entry.string()
            }
        }
        entries += construct(context, reader.target) { make(id!!, description!!) }
    }
    return entries
}

// Reads a kind member, checking it against an already-known expected kind when the caller wants
// one specific concrete class instead of the general interface.
internal fun readKind(value: MemberValue, expected: QuestionKind?): QuestionKind {
    val wireName = value.string()
    if (expected != null && wireName != expected.wireName) value.invalid(expected.wireName)
    return KINDS_BY_WIRE_NAME[wireName] ?: value.invalid(KIND_EXPECTED)
}

// Rejects options or levels that do not belong to the given kind, and requires them when they do.
internal fun checkOptionsAndLevels(
    context: DeserializationContext,
    reader: StrictObjectReader,
    kind: QuestionKind,
    options: Any?,
    levels: Any?,
) {
    if (kind != QuestionKind.CHOICE && options != null) reader.unknownMember(context, "options")
    if (kind != QuestionKind.RATING && levels != null) reader.unknownMember(context, "levels")
    if (kind == QuestionKind.CHOICE && options == null) reader.missingMember(context, "options")
    if (kind == QuestionKind.RATING && levels == null) reader.missingMember(context, "levels")
}
