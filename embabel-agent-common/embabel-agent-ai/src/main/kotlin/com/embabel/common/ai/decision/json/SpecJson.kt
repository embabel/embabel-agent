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
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionOptions
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.ExecutionMode
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.Question
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingLevel
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.json.StrictObjectReader.MemberValue
import org.jetbrains.annotations.ApiStatus
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.exc.MismatchedInputException
import tools.jackson.databind.module.SimpleDeserializers
import tools.jackson.databind.module.SimpleSerializers
import java.util.Locale

/**
 * The JSON bindings for the spec side of the decision types: the three question classes and
 * [Question], [DecisionSpec], [DecisionRequest], [DecisionOptions] and [DecisionCapabilities].
 *
 * Writers put out fixed member names in a fixed order and never consult the mapper's naming
 * strategy. Readers go through [StrictObjectReader] and build every value through the public
 * builders and factories, so each invariant those enforce also holds for JSON input. A validation
 * failure becomes a `MismatchedInputException` with the same message and the original exception
 * as its cause.
 */
@ApiStatus.Internal
internal object SpecJson {

    /**
     * Adds the spec-side bindings to the given collections.
     *
     * @param serializers receives one serializer per type
     * @param deserializers receives one deserializer per type
     */
    fun register(serializers: SimpleSerializers, deserializers: SimpleDeserializers) {
        // Question is an interface, so this one serializer also covers the three question classes.
        serializers.addSerializer(Question::class.java, QuestionSerializer)
        serializers.addSerializer(DecisionSpec::class.java, DecisionSpecSerializer)
        serializers.addSerializer(DecisionRequest::class.java, DecisionRequestSerializer)
        serializers.addSerializer(DecisionOptions::class.java, DecisionOptionsSerializer)
        serializers.addSerializer(DecisionCapabilities::class.java, DecisionCapabilitiesSerializer)

        deserializers.addDeserializer(Question::class.java, QuestionDeserializer(Question::class.java, null))
        deserializers.addDeserializer(
            PropositionQuestionSpec::class.java,
            QuestionDeserializer(PropositionQuestionSpec::class.java, QuestionKind.PROPOSITION),
        )
        deserializers.addDeserializer(
            ChoiceQuestionSpec::class.java,
            QuestionDeserializer(ChoiceQuestionSpec::class.java, QuestionKind.CHOICE),
        )
        deserializers.addDeserializer(
            RatingQuestionSpec::class.java,
            QuestionDeserializer(RatingQuestionSpec::class.java, QuestionKind.RATING),
        )
        deserializers.addDeserializer(DecisionSpec::class.java, DecisionSpecDeserializer)
        deserializers.addDeserializer(DecisionRequest::class.java, DecisionRequestDeserializer)
        deserializers.addDeserializer(DecisionOptions::class.java, DecisionOptionsDeserializer)
        deserializers.addDeserializer(DecisionCapabilities::class.java, DecisionCapabilitiesDeserializer)
    }
}

// Wire names. Question kinds take theirs from QuestionKind; execution modes use the lower-case
// constant name, which is already snake case.
private fun ExecutionMode.wireName(): String = name.lowercase(Locale.ROOT)

// Reverse lookups from wire name to constant, in declaration order so messages list them that way.
private val KINDS_BY_WIRE_NAME: Map<String, QuestionKind> = QuestionKind.entries.associateBy { it.wireName }
private val MODES_BY_WIRE_NAME: Map<String, ExecutionMode> = ExecutionMode.entries.associateBy { it.wireName() }

private val KIND_EXPECTED = "one of " + KINDS_BY_WIRE_NAME.keys.joinToString(", ")

private val QUESTION_MEMBERS = listOf("kind", "name", "instructions")

// Reads a question whose kind is not known in advance. The kind-specific members are optional
// here and checked against the kind once the whole object has been read.
private val QUESTION_READER = StrictObjectReader(Question::class.java, QUESTION_MEMBERS, listOf("options", "levels"))

// Reads a question requested as one specific class. These also name the class when a member
// belongs to a different kind.
private val KIND_READERS: Map<QuestionKind, StrictObjectReader> = mapOf(
    QuestionKind.PROPOSITION to StrictObjectReader(PropositionQuestionSpec::class.java, QUESTION_MEMBERS),
    QuestionKind.CHOICE to StrictObjectReader(ChoiceQuestionSpec::class.java, QUESTION_MEMBERS + "options"),
    QuestionKind.RATING to StrictObjectReader(RatingQuestionSpec::class.java, QUESTION_MEMBERS + "levels"),
)

private val OPTION_READER = StrictObjectReader(Category::class.java, listOf("id", "description"))
private val LEVEL_READER = StrictObjectReader(RatingLevel::class.java, listOf("id", "description"))
private val SPEC_READER = StrictObjectReader(DecisionSpec::class.java, listOf("questions"))
private val REQUEST_READER = StrictObjectReader(DecisionRequest::class.java, listOf("input", "spec"))
private val OPTIONS_READER = StrictObjectReader(DecisionOptions::class.java, listOf("executionModes"))
private val CAPABILITIES_READER = StrictObjectReader(
    DecisionCapabilities::class.java,
    required = listOf("questionKinds", "executionModes"),
    optional = listOf("maxQuestions", "maxInputCharacters"),
)

// Starts reading one object with the given reader and passes each member to the handler. It is
// either a top-level read on the parser or a nested read through a MemberValue.
private typealias ObjectRead = (StrictObjectReader, (String, MemberValue) -> Unit) -> Unit

private fun topLevel(parser: JsonParser, context: DeserializationContext): ObjectRead =
    { reader, handler -> reader.read(parser, context, handler) }

private fun nested(value: MemberValue): ObjectRead =
    { reader, handler -> value.readObject(reader, handler) }

// Runs a public builder or factory and turns its validation failure into an input mismatch that
// keeps the message. The message is passed unformatted, so a '%' in it is harmless.
private inline fun <T> construct(context: DeserializationContext, target: Class<*>, make: () -> T): T =
    try {
        make()
    } catch (e: IllegalArgumentException) {
        throw MismatchedInputException.from(context.parser, target, e.message ?: "Invalid ${target.simpleName}")
            .withCause(e)
    }

private fun writeEntries(generator: JsonGenerator, member: String, entries: List<Pair<String, String>>) {
    generator.writeArrayPropertyStart(member)
    for ((id, description) in entries) {
        generator.writeStartObject()
        generator.writeStringProperty("id", id)
        generator.writeStringProperty("description", description)
        generator.writeEndObject()
    }
    generator.writeEndArray()
}

private fun writeQuestion(generator: JsonGenerator, question: Question<*>) {
    generator.writeStartObject()
    generator.writeStringProperty("kind", question.kind.wireName)
    generator.writeStringProperty("name", question.name)
    generator.writeStringProperty("instructions", question.instructions)
    when (question) {
        is PropositionQuestionSpec -> Unit
        is ChoiceQuestionSpec -> writeEntries(generator, "options", question.options.map { it.id to it.description })
        is RatingQuestionSpec -> writeEntries(generator, "levels", question.levels.map { it.id to it.description })
    }
    generator.writeEndObject()
}

private fun writeSpec(generator: JsonGenerator, spec: DecisionSpec) {
    generator.writeStartObject()
    generator.writeArrayPropertyStart("questions")
    spec.questions.forEach { writeQuestion(generator, it) }
    generator.writeEndArray()
    generator.writeEndObject()
}

private fun writeWireNames(generator: JsonGenerator, member: String, wireNames: List<String>) {
    generator.writeArrayPropertyStart(member)
    wireNames.forEach { generator.writeString(it) }
    generator.writeEndArray()
}

private fun readEntries(value: MemberValue, reader: StrictObjectReader): List<Pair<String, String>> {
    val entries = ArrayList<Pair<String, String>>()
    value.readArray { element ->
        var id: String? = null
        var description: String? = null
        element.readObject(reader) { member, entry ->
            when (member) {
                "id" -> id = entry.string()
                "description" -> description = entry.string()
            }
        }
        // Both members are required, so the reader has already rejected an entry missing either.
        entries += id!! to description!!
    }
    return entries
}

// Reads a JSON array of wire names into a set. Unknown names and repeats are both rejected, so
// the set always has one constant per element.
private fun <E : Enum<E>> readWireNames(value: MemberValue, byWireName: Map<String, E>): Set<E> {
    val result = LinkedHashSet<E>()
    value.readArray { element ->
        val wireName = element.string()
        val constant = byWireName[wireName] ?: element.invalid("one of " + byWireName.keys.joinToString(", "))
        if (!result.add(constant)) element.invalid("a value that appears once")
    }
    return result
}

// Collects the members of one question in document order, then builds it once the object is closed.
private class QuestionParts(private val expected: QuestionKind?) {
    var kind: QuestionKind? = null
    var name: String? = null
    var instructions: String? = null
    var options: List<Pair<String, String>>? = null
    var levels: List<Pair<String, String>>? = null

    val reader: StrictObjectReader = expected?.let(KIND_READERS::getValue) ?: QUESTION_READER

    fun accept(member: String, value: MemberValue) {
        when (member) {
            "kind" -> kind = readKind(value)
            "name" -> name = value.string()
            "instructions" -> instructions = value.string()
            "options" -> options = readEntries(value, OPTION_READER)
            "levels" -> levels = readEntries(value, LEVEL_READER)
        }
    }

    private fun readKind(value: MemberValue): QuestionKind {
        val wireName = value.string()
        if (expected != null && wireName != expected.wireName) value.invalid(expected.wireName)
        return KINDS_BY_WIRE_NAME[wireName] ?: value.invalid(KIND_EXPECTED)
    }

    fun build(context: DeserializationContext): Question<*> {
        // The reader has already required kind, name and instructions.
        val kind = kind!!
        val kindReader = KIND_READERS.getValue(kind)
        if (kind != QuestionKind.CHOICE && options != null) kindReader.unknownMember(context, "options")
        if (kind != QuestionKind.RATING && levels != null) kindReader.unknownMember(context, "levels")
        if (kind == QuestionKind.CHOICE && options == null) kindReader.missingMember(context, "options")
        if (kind == QuestionKind.RATING && levels == null) kindReader.missingMember(context, "levels")
        return construct(context, kindReader.target) {
            val named = Questions.named(name!!)
            when (kind) {
                QuestionKind.PROPOSITION -> named.proposition(instructions!!).build()
                QuestionKind.CHOICE -> named.choice(instructions!!)
                    .apply { options!!.forEach { (id, description) -> option(id, description) } }
                    .build()
                QuestionKind.RATING -> named.rating(instructions!!)
                    .apply { levels!!.forEach { (id, description) -> level(id, description) } }
                    .build()
            }
        }
    }
}

private fun readQuestion(context: DeserializationContext, expected: QuestionKind?, read: ObjectRead): Question<*> {
    val parts = QuestionParts(expected)
    read(parts.reader, parts::accept)
    return parts.build(context)
}

private fun readSpec(context: DeserializationContext, read: ObjectRead): DecisionSpec {
    var questions: List<Question<*>>? = null
    read(SPEC_READER) { member, value ->
        if (member == "questions") {
            val list = ArrayList<Question<*>>()
            value.readArray { element -> list += readQuestion(context, null, nested(element)) }
            questions = list
        }
    }
    return construct(context, DecisionSpec::class.java) { DecisionSpec.of(questions!!) }
}

private object QuestionSerializer : ValueSerializer<Question<*>>() {
    override fun serialize(value: Question<*>, generator: JsonGenerator, context: SerializationContext) =
        writeQuestion(generator, value)
}

private object DecisionSpecSerializer : ValueSerializer<DecisionSpec>() {
    override fun serialize(value: DecisionSpec, generator: JsonGenerator, context: SerializationContext) =
        writeSpec(generator, value)
}

private object DecisionRequestSerializer : ValueSerializer<DecisionRequest>() {
    override fun serialize(value: DecisionRequest, generator: JsonGenerator, context: SerializationContext) {
        generator.writeStartObject()
        generator.writeStringProperty("input", value.input)
        generator.writeName("spec")
        writeSpec(generator, value.spec)
        generator.writeEndObject()
    }
}

private object DecisionOptionsSerializer : ValueSerializer<DecisionOptions>() {
    override fun serialize(value: DecisionOptions, generator: JsonGenerator, context: SerializationContext) {
        generator.writeStartObject()
        writeWireNames(generator, "executionModes", value.executionModes.map { it.wireName() })
        generator.writeEndObject()
    }
}

private object DecisionCapabilitiesSerializer : ValueSerializer<DecisionCapabilities>() {
    override fun serialize(value: DecisionCapabilities, generator: JsonGenerator, context: SerializationContext) {
        generator.writeStartObject()
        writeWireNames(generator, "questionKinds", value.questionKinds.map { it.wireName })
        writeWireNames(generator, "executionModes", value.executionModes.map { it.wireName() })
        value.maxQuestions?.let { generator.writeNumberProperty("maxQuestions", it) }
        value.maxInputCharacters?.let { generator.writeNumberProperty("maxInputCharacters", it) }
        generator.writeEndObject()
    }
}

// One class serves Question and each concrete question class. With a kind, the JSON must carry
// that kind and only that kind's members.
private class QuestionDeserializer<Q : Question<*>>(
    private val type: Class<Q>,
    private val kind: QuestionKind?,
) : ValueDeserializer<Q>() {
    override fun handledType(): Class<*> = type

    override fun deserialize(parser: JsonParser, context: DeserializationContext): Q =
        type.cast(readQuestion(context, kind, topLevel(parser, context)))
}

private object DecisionSpecDeserializer : ValueDeserializer<DecisionSpec>() {
    override fun handledType(): Class<*> = DecisionSpec::class.java

    override fun deserialize(parser: JsonParser, context: DeserializationContext): DecisionSpec =
        readSpec(context, topLevel(parser, context))
}

private object DecisionRequestDeserializer : ValueDeserializer<DecisionRequest>() {
    override fun handledType(): Class<*> = DecisionRequest::class.java

    override fun deserialize(parser: JsonParser, context: DeserializationContext): DecisionRequest {
        var input: String? = null
        var spec: DecisionSpec? = null
        REQUEST_READER.read(parser, context) { member, value ->
            when (member) {
                "input" -> input = value.string()
                "spec" -> spec = readSpec(context, nested(value))
            }
        }
        return construct(context, DecisionRequest::class.java) { DecisionRequest.of(input!!, spec!!) }
    }
}

private object DecisionOptionsDeserializer : ValueDeserializer<DecisionOptions>() {
    override fun handledType(): Class<*> = DecisionOptions::class.java

    override fun deserialize(parser: JsonParser, context: DeserializationContext): DecisionOptions {
        var modes: Set<ExecutionMode>? = null
        OPTIONS_READER.read(parser, context) { member, value ->
            if (member == "executionModes") modes = readWireNames(value, MODES_BY_WIRE_NAME)
        }
        return construct(context, DecisionOptions::class.java) { DecisionOptions.of(modes!!) }
    }
}

private object DecisionCapabilitiesDeserializer : ValueDeserializer<DecisionCapabilities>() {
    override fun handledType(): Class<*> = DecisionCapabilities::class.java

    override fun deserialize(parser: JsonParser, context: DeserializationContext): DecisionCapabilities {
        var kinds: Set<QuestionKind>? = null
        var modes: Set<ExecutionMode>? = null
        var maxQuestions: Int? = null
        var maxInputCharacters: Int? = null
        CAPABILITIES_READER.read(parser, context) { member, value ->
            when (member) {
                "questionKinds" -> kinds = readWireNames(value, KINDS_BY_WIRE_NAME)
                "executionModes" -> modes = readWireNames(value, MODES_BY_WIRE_NAME)
                // An explicit null reads the same as an absent member: the limit is not reported.
                "maxQuestions" -> maxQuestions = value.orNull { it.int() }
                "maxInputCharacters" -> maxInputCharacters = value.orNull { it.int() }
            }
        }
        return construct(context, DecisionCapabilities::class.java) {
            var capabilities = DecisionCapabilities.of(kinds!!, modes!!)
            maxQuestions?.let { capabilities = capabilities.withMaxQuestions(it) }
            maxInputCharacters?.let { capabilities = capabilities.withMaxInputCharacters(it) }
            capabilities
        }
    }
}
