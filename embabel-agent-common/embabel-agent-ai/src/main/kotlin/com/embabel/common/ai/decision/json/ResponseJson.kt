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
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionAnswer
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.ExecutionMode
import com.embabel.common.ai.decision.LevelProbability
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.RatingLevel
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.RatingScore
import com.embabel.common.ai.decision.RatingStatistic
import com.embabel.common.ai.decision.json.StrictObjectReader.MemberValue
import org.jetbrains.annotations.ApiStatus
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleDeserializers
import tools.jackson.databind.module.SimpleSerializers

/**
 * The JSON bindings for the response side of the decision types: [DecisionResponse],
 * [DecisionAnswer] and its three classes, and [RatingResult] and its three classes.
 *
 * A response is written as its spec id, its execution mode, its request failure when there is
 * one, and an `answers` object that maps each question name to its answer in spec order. An
 * answer carries its kind, definition id, options or levels, and outcome. Each outcome has a
 * `status` member that says which result class it is.
 *
 * Proposition and choice outcomes use the existing result classes. This file writes and reads
 * those outcomes, and their provenance, itself, so the module adds no binding for any existing
 * type and their JSON everywhere else stays the same.
 *
 * Readers go through [StrictObjectReader] and build every answer and response through the
 * validating factories on the response types. A validation failure becomes a
 * `MismatchedInputException` with the same message and the original exception as its cause.
 */
@ApiStatus.Internal
internal object ResponseJson {

    /**
     * Adds the response-side bindings to the given collections.
     *
     * @param serializers receives one serializer per type
     * @param deserializers receives one deserializer per type
     */
    fun register(serializers: SimpleSerializers, deserializers: SimpleDeserializers) {
        // DecisionAnswer and RatingResult are interfaces, so one serializer each also covers their classes.
        serializers.addSerializer(DecisionResponse::class.java, DecisionResponseSerializer)
        serializers.addSerializer(DecisionAnswer::class.java, DecisionAnswerSerializer)
        serializers.addSerializer(RatingResult::class.java, RatingResultSerializer)

        deserializers.addDeserializer(DecisionResponse::class.java, DecisionResponseDeserializer)
        deserializers.addDeserializer(DecisionAnswer::class.java, AnswerDeserializer(DecisionAnswer::class.java, null))
        deserializers.addDeserializer(
            DecisionAnswer.Proposition::class.java,
            AnswerDeserializer(DecisionAnswer.Proposition::class.java, QuestionKind.PROPOSITION),
        )
        deserializers.addDeserializer(
            DecisionAnswer.Choice::class.java,
            AnswerDeserializer(DecisionAnswer.Choice::class.java, QuestionKind.CHOICE),
        )
        deserializers.addDeserializer(
            DecisionAnswer.Rating::class.java,
            AnswerDeserializer(DecisionAnswer.Rating::class.java, QuestionKind.RATING),
        )
        deserializers.addDeserializer(RatingResult::class.java, RatingResultDeserializer(RatingResult::class.java, null))
        deserializers.addDeserializer(
            RatingResult.Answered::class.java,
            RatingResultDeserializer(RatingResult.Answered::class.java, ANSWERED),
        )
        deserializers.addDeserializer(
            RatingResult.Inconclusive::class.java,
            RatingResultDeserializer(RatingResult.Inconclusive::class.java, INCONCLUSIVE),
        )
        deserializers.addDeserializer(
            RatingResult.Failure::class.java,
            RatingResultDeserializer(RatingResult.Failure::class.java, FAILURE),
        )
    }
}

// Status wire names.
private const val ANSWERED = "answered"
private const val SELECTED = "selected"
private const val NO_MATCH = "no_match"
private const val INCONCLUSIVE = "inconclusive"
private const val FAILURE = "failure"

// Failure reasons and rating statistics use the lower-case constant name, which is already snake
// case, the same as execution modes. Question kinds take theirs from QuestionKind.
private fun <E : Enum<E>> bySnakeName(entries: List<E>): Map<String, E> = entries.associateBy { it.snakeName }

private val REASONS_BY_WIRE_NAME: Map<String, FailureReason> = bySnakeName(FailureReason.entries)
private val STATISTICS_BY_WIRE_NAME: Map<String, RatingStatistic> = bySnakeName(RatingStatistic.entries)

// The members one outcome status requires and allows, besides status itself.
private class StatusShape(val required: Set<String>, val optional: Set<String> = emptySet())

private val PROVENANCE_ONLY = StatusShape(setOf("provenance"))
private val FAILURE_SHAPE = StatusShape(setOf("reason"))

// The statuses of one kind's result type. The reader accepts every member any of those statuses
// allows; which of them fit the status is checked once the outcome object is closed, because the
// status may come after the other members.
private class KindOutcomes(val type: Class<*>, val statuses: Map<String, StatusShape>) {
    val members: List<String> = statuses.values.flatMap { it.required + it.optional }.distinct()
    val reader = StrictObjectReader(type, required = listOf("status"), optional = members)
    val expected = "one of " + statuses.keys.joinToString(", ")
}

private val OUTCOMES: Map<QuestionKind, KindOutcomes> = mapOf(
    QuestionKind.PROPOSITION to KindOutcomes(
        PropositionResult::class.java,
        linkedMapOf(
            ANSWERED to StatusShape(setOf("answer", "provenance"), setOf("pTrue")),
            INCONCLUSIVE to PROVENANCE_ONLY,
            FAILURE to FAILURE_SHAPE,
        ),
    ),
    QuestionKind.CHOICE to KindOutcomes(
        ClassificationResult::class.java,
        linkedMapOf(
            SELECTED to StatusShape(setOf("categoryId", "provenance"), setOf("confidence")),
            NO_MATCH to PROVENANCE_ONLY,
            INCONCLUSIVE to PROVENANCE_ONLY,
            FAILURE to FAILURE_SHAPE,
        ),
    ),
    QuestionKind.RATING to KindOutcomes(
        RatingResult::class.java,
        linkedMapOf(
            ANSWERED to StatusShape(setOf("provenance"), setOf("selectedLevelId", "distribution", "score", "confidence")),
            INCONCLUSIVE to PROVENANCE_ONLY,
            FAILURE to FAILURE_SHAPE,
        ),
    ),
)

// Reads an outcome that comes before its answer's kind. It allows the members of every kind, and
// the kind's own rules run once the answer object is closed.
private val ANY_OUTCOME_READER = StrictObjectReader(
    DecisionAnswer::class.java,
    required = listOf("status"),
    optional = OUTCOMES.values.flatMap { it.members }.distinct(),
)

private val PROVENANCE_READER = StrictObjectReader(
    ModelProvenance::class.java,
    required = listOf("modelName", "provider"),
    optional = listOf("version", "requestId"),
)
private val PROBABILITY_READER = StrictObjectReader(LevelProbability::class.java, listOf("levelId", "probability"))
private val SCORE_READER = StrictObjectReader(RatingScore::class.java, listOf("value", "statistic"))

private val RESPONSE_READER = StrictObjectReader(
    DecisionResponse::class.java,
    required = listOf("definitionId", "executionMode", "answers"),
    optional = listOf("requestFailure"),
)

// The answers object maps names to answers, so any name is allowed. A repeated name is still a
// duplicate member and fails.
private val ANSWERS_READER = StrictObjectReader.anyMembers(DecisionResponse::class.java)

// An answer inside a response takes its name from its key. An answer on its own carries a name member.
private val ANSWER_IN_RESPONSE_READER =
    StrictObjectReader(DecisionAnswer::class.java, listOf("kind", "definitionId", "outcome"), listOf("options", "levels"))
private val ANSWER_READER =
    StrictObjectReader(DecisionAnswer::class.java, listOf("kind", "name", "definitionId", "outcome"), listOf("options", "levels"))

private fun <E> readWireName(value: MemberValue, byWireName: Map<String, E>): E =
    byWireName[value.string()] ?: value.invalid("one of " + byWireName.keys.joinToString(", "))

private fun writeProvenance(generator: JsonGenerator, provenance: ModelProvenance) {
    generator.writeObjectPropertyStart("provenance")
    generator.writeStringProperty("modelName", provenance.modelName)
    generator.writeStringProperty("provider", provenance.provider)
    provenance.version?.let { generator.writeStringProperty("version", it) }
    provenance.requestId?.let { generator.writeStringProperty("requestId", it) }
    generator.writeEndObject()
}

// Writes an outcome whose only evidence is its provenance, such as no match or inconclusive.
private fun writeInconclusive(generator: JsonGenerator, status: String, provenance: ModelProvenance) {
    generator.writeStringProperty("status", status)
    writeProvenance(generator, provenance)
}

private fun writeFailure(generator: JsonGenerator, reason: FailureReason) {
    generator.writeStringProperty("status", FAILURE)
    generator.writeStringProperty("reason", reason.snakeName)
}

private fun writePropositionOutcome(generator: JsonGenerator, result: PropositionResult) {
    generator.writeStartObject()
    when (result) {
        is PropositionResult.Answered -> {
            generator.writeStringProperty("status", ANSWERED)
            generator.writeBooleanProperty("answer", result.answer)
            result.pTrue?.let { generator.writeNumberProperty("pTrue", it) }
            writeProvenance(generator, result.provenance)
        }
        is PropositionResult.Inconclusive -> writeInconclusive(generator, INCONCLUSIVE, result.provenance)
        is PropositionResult.Failure -> writeFailure(generator, result.reason)
    }
    generator.writeEndObject()
}

private fun writeChoiceOutcome(generator: JsonGenerator, result: ClassificationResult) {
    generator.writeStartObject()
    when (result) {
        is ClassificationResult.Selected -> {
            generator.writeStringProperty("status", SELECTED)
            generator.writeStringProperty("categoryId", result.categoryId)
            result.confidence?.let { generator.writeNumberProperty("confidence", it) }
            writeProvenance(generator, result.provenance)
        }
        is ClassificationResult.NoMatch -> writeInconclusive(generator, NO_MATCH, result.provenance)
        is ClassificationResult.Inconclusive -> writeInconclusive(generator, INCONCLUSIVE, result.provenance)
        is ClassificationResult.Failure -> writeFailure(generator, result.reason)
    }
    generator.writeEndObject()
}

private fun writeRatingOutcome(generator: JsonGenerator, result: RatingResult) {
    generator.writeStartObject()
    when (result) {
        is RatingResult.Answered -> {
            generator.writeStringProperty("status", ANSWERED)
            result.selectedLevelId?.let { generator.writeStringProperty("selectedLevelId", it) }
            if (result.distribution.isNotEmpty()) {
                generator.writeArrayPropertyStart("distribution")
                for (entry in result.distribution) {
                    generator.writeStartObject()
                    generator.writeStringProperty("levelId", entry.levelId)
                    generator.writeNumberProperty("probability", entry.probability)
                    generator.writeEndObject()
                }
                generator.writeEndArray()
            }
            result.score?.let {
                generator.writeObjectPropertyStart("score")
                generator.writeNumberProperty("value", it.value)
                generator.writeStringProperty("statistic", it.statistic.snakeName)
                generator.writeEndObject()
            }
            result.confidence?.let { generator.writeNumberProperty("confidence", it) }
            writeProvenance(generator, result.provenance)
        }
        is RatingResult.Inconclusive -> writeInconclusive(generator, INCONCLUSIVE, result.provenance)
        is RatingResult.Failure -> writeFailure(generator, result.reason)
    }
    generator.writeEndObject()
}

private fun writeAnswer(generator: JsonGenerator, answer: DecisionAnswer, withName: Boolean) {
    generator.writeStartObject()
    generator.writeStringProperty("kind", answer.kind.wireName)
    if (withName) generator.writeStringProperty("name", answer.name)
    generator.writeStringProperty("definitionId", answer.definitionId)
    when (answer) {
        is DecisionAnswer.Proposition -> {
            generator.writeName("outcome")
            writePropositionOutcome(generator, answer.outcome)
        }
        is DecisionAnswer.Choice -> {
            writeEntries(generator, "options", answer.options.map { it.id to it.description })
            generator.writeName("outcome")
            writeChoiceOutcome(generator, answer.outcome)
        }
        is DecisionAnswer.Rating -> {
            writeEntries(generator, "levels", answer.levels.map { it.id to it.description })
            generator.writeName("outcome")
            writeRatingOutcome(generator, answer.outcome)
        }
    }
    generator.writeEndObject()
}

private object DecisionResponseSerializer : ValueSerializer<DecisionResponse>() {
    override fun serialize(value: DecisionResponse, generator: JsonGenerator, context: SerializationContext) {
        generator.writeStartObject()
        generator.writeStringProperty("definitionId", value.definitionId)
        generator.writeStringProperty("executionMode", value.executionMode.snakeName)
        value.requestFailure?.let { generator.writeStringProperty("requestFailure", it.snakeName) }
        generator.writeObjectPropertyStart("answers")
        for (answer in value.answers) {
            generator.writeName(answer.name)
            writeAnswer(generator, answer, withName = false)
        }
        generator.writeEndObject()
        generator.writeEndObject()
    }
}

private object DecisionAnswerSerializer : ValueSerializer<DecisionAnswer>() {
    override fun serialize(value: DecisionAnswer, generator: JsonGenerator, context: SerializationContext) =
        writeAnswer(generator, value, withName = true)
}

private object RatingResultSerializer : ValueSerializer<RatingResult>() {
    override fun serialize(value: RatingResult, generator: JsonGenerator, context: SerializationContext) =
        writeRatingOutcome(generator, value)
}

private fun readProvenance(context: DeserializationContext, value: MemberValue): ModelProvenance {
    var modelName: String? = null
    var provider: String? = null
    var version: String? = null
    var requestId: String? = null
    value.readObject(PROVENANCE_READER) { member, entry ->
        when (member) {
            "modelName" -> modelName = entry.string()
            "provider" -> provider = entry.string()
            "version" -> version = entry.orNull { it.string() }
            "requestId" -> requestId = entry.orNull { it.string() }
        }
    }
    return construct(context, ModelProvenance::class.java) { ModelProvenance(modelName!!, provider!!, version, requestId) }
}

private fun readDistribution(context: DeserializationContext, value: MemberValue): List<LevelProbability> {
    val distribution = ArrayList<LevelProbability>()
    value.readArray { element ->
        var levelId: String? = null
        var probability: Double? = null
        element.readObject(PROBABILITY_READER) { member, entry ->
            when (member) {
                "levelId" -> levelId = entry.string()
                "probability" -> probability = entry.double()
            }
        }
        distribution += construct(context, LevelProbability::class.java) { LevelProbability(levelId!!, probability!!) }
    }
    return distribution
}

private fun readScore(context: DeserializationContext, value: MemberValue): RatingScore {
    var number: Double? = null
    var statistic: RatingStatistic? = null
    value.readObject(SCORE_READER) { member, entry ->
        when (member) {
            "value" -> number = entry.double()
            "statistic" -> statistic = readWireName(entry, STATISTICS_BY_WIRE_NAME)
        }
    }
    return construct(context, RatingScore::class.java) { RatingScore(number!!, statistic!!) }
}

// Collects the members of one outcome in document order. The status decides which of them are
// allowed, so the checks and the result are built once the outcome object is closed.
private class OutcomeParts(private val context: DeserializationContext) {
    private val seen = ArrayList<String>()
    private var status: String? = null
    private var answer: Boolean? = null
    private var pTrue: Double? = null
    private var categoryId: String? = null
    private var selectedLevelId: String? = null
    private var distribution: List<LevelProbability>? = null
    private var score: RatingScore? = null
    private var confidence: Double? = null
    private var reason: FailureReason? = null
    private var provenance: ModelProvenance? = null

    // Members that are only ever optional read an explicit null as absent.
    fun accept(member: String, value: MemberValue) {
        seen += member
        when (member) {
            "status" -> status = value.string()
            "answer" -> answer = value.boolean()
            "pTrue" -> pTrue = value.orNull { it.double() }
            "categoryId" -> categoryId = value.string()
            "selectedLevelId" -> selectedLevelId = value.orNull { it.string() }
            "distribution" -> distribution = value.orNull { readDistribution(context, it) }
            "score" -> score = value.orNull { readScore(context, it) }
            "confidence" -> confidence = value.orNull { it.double() }
            "reason" -> reason = readWireName(value, REASONS_BY_WIRE_NAME)
            "provenance" -> provenance = readProvenance(context, value)
        }
    }

    fun proposition(): PropositionResult = build(QuestionKind.PROPOSITION, null) { status ->
        when (status) {
            ANSWERED -> PropositionResult.Answered(answer!!, provenance!!, pTrue)
            INCONCLUSIVE -> PropositionResult.Inconclusive(provenance!!)
            else -> PropositionResult.Failure(reason!!)
        }
    }

    fun choice(): ClassificationResult = build(QuestionKind.CHOICE, null) { status ->
        when (status) {
            SELECTED -> ClassificationResult.Selected(categoryId!!, provenance!!, confidence)
            NO_MATCH -> ClassificationResult.NoMatch(provenance!!)
            INCONCLUSIVE -> ClassificationResult.Inconclusive(provenance!!)
            else -> ClassificationResult.Failure(reason!!)
        }
    }

    fun rating(expectedStatus: String? = null): RatingResult = build(QuestionKind.RATING, expectedStatus) { status ->
        when (status) {
            ANSWERED -> RatingResult.Answered(provenance!!, selectedLevelId, distribution ?: emptyList(), score, confidence)
            INCONCLUSIVE -> RatingResult.Inconclusive(provenance!!)
            else -> RatingResult.Failure(reason!!)
        }
    }

    // Checks the members against the status, then builds the result. Each status's required
    // members are present once this check passes, so the builders above can rely on them.
    private inline fun <T> build(kind: QuestionKind, expectedStatus: String?, make: (String) -> T): T {
        val outcomes = OUTCOMES.getValue(kind)
        val reader = outcomes.reader
        // Every outcome reader requires status, so it is set here.
        val status = status!!
        if (expectedStatus != null && status != expectedStatus) reader.invalidMember(context, "status", expectedStatus)
        val shape = outcomes.statuses[status] ?: reader.invalidMember(context, "status", outcomes.expected)
        seen.firstOrNull { it != "status" && it !in shape.required && it !in shape.optional }
            ?.let { reader.unknownMember(context, it) }
        shape.required.firstOrNull { it !in seen }?.let { reader.missingMember(context, it) }
        return construct(context, outcomes.type) { make(status) }
    }
}

// Collects the members of one answer in document order, then builds it once the object is closed.
private class AnswerParts(
    private val context: DeserializationContext,
    private val expected: QuestionKind?,
    private var name: String?,
) {
    // A name known in advance comes from the answers object, so the answer itself has no name member.
    val reader: StrictObjectReader = if (name == null) ANSWER_READER else ANSWER_IN_RESPONSE_READER

    private var kind: QuestionKind? = null
    private var definitionId: String? = null
    private var options: List<Category>? = null
    private var levels: List<RatingLevel>? = null
    private var outcome: OutcomeParts? = null

    fun accept(member: String, value: MemberValue) {
        when (member) {
            "kind" -> kind = readKind(value, expected)
            "name" -> name = value.string()
            "definitionId" -> definitionId = value.string()
            "options" -> options = readEntries(context, value, OPTION_READER, ::Category)
            "levels" -> levels = readEntries(context, value, LEVEL_READER, ::RatingLevel)
            "outcome" -> {
                val parts = OutcomeParts(context)
                // The kind usually comes first. When it has not been seen yet, every kind's members
                // are allowed and the kind's own rules run in build.
                val outcomeReader = (expected ?: kind)?.let { OUTCOMES.getValue(it).reader } ?: ANY_OUTCOME_READER
                value.readObject(outcomeReader, parts::accept)
                outcome = parts
            }
        }
    }

    fun build(): DecisionAnswer {
        // The reader has already required kind, name, definitionId and outcome.
        val kind = kind!!
        checkOptionsAndLevels(context, reader, kind, options, levels)
        val outcome = outcome!!
        val name = name!!
        val definitionId = definitionId!!
        return when (kind) {
            QuestionKind.PROPOSITION -> outcome.proposition().let {
                construct(context, DecisionAnswer::class.java) { DecisionAnswer.Proposition.create(name, definitionId, it) }
            }
            QuestionKind.CHOICE -> outcome.choice().let {
                construct(context, DecisionAnswer::class.java) { DecisionAnswer.Choice.create(name, definitionId, options!!, it) }
            }
            QuestionKind.RATING -> outcome.rating().let {
                construct(context, DecisionAnswer::class.java) { DecisionAnswer.Rating.create(name, definitionId, levels!!, it) }
            }
        }
    }
}

private fun readAnswer(
    context: DeserializationContext,
    expected: QuestionKind?,
    name: String?,
    start: ObjectRead,
): DecisionAnswer {
    val parts = AnswerParts(context, expected, name)
    start(parts.reader, parts::accept)
    return parts.build()
}

private object DecisionResponseDeserializer : ValueDeserializer<DecisionResponse>() {
    override fun handledType(): Class<*> = DecisionResponse::class.java

    override fun deserialize(parser: JsonParser, context: DeserializationContext): DecisionResponse {
        var definitionId: String? = null
        var executionMode: ExecutionMode? = null
        var requestFailure: FailureReason? = null
        var answers: List<DecisionAnswer>? = null
        RESPONSE_READER.read(parser, context) { member, value ->
            when (member) {
                "definitionId" -> definitionId = value.string()
                "executionMode" -> executionMode = readWireName(value, MODES_BY_WIRE_NAME)
                // An explicit null reads the same as an absent member: the request did not fail.
                "requestFailure" -> requestFailure = value.orNull { readWireName(it, REASONS_BY_WIRE_NAME) }
                "answers" -> {
                    // Answers stay in document order. The factory's spec id check rejects any other order.
                    val list = ArrayList<DecisionAnswer>()
                    value.readObject(ANSWERS_READER) { name, answer ->
                        list += readAnswer(context, null, name, nested(answer))
                    }
                    answers = list
                }
            }
        }
        return construct(context, DecisionResponse::class.java) {
            DecisionResponse.create(definitionId!!, executionMode!!, requestFailure, answers!!)
        }
    }
}

// One class serves DecisionAnswer and each answer class. With a kind, the JSON must carry that kind.
private class AnswerDeserializer<A : DecisionAnswer>(
    private val type: Class<A>,
    private val kind: QuestionKind?,
) : ValueDeserializer<A>() {
    override fun handledType(): Class<*> = type

    override fun deserialize(parser: JsonParser, context: DeserializationContext): A =
        type.cast(readAnswer(context, kind, null, topLevel(parser, context)))
}

// One class serves RatingResult and each result class. With a status, the JSON must carry that status.
private class RatingResultDeserializer<R : RatingResult>(
    private val type: Class<R>,
    private val status: String?,
) : ValueDeserializer<R>() {
    override fun handledType(): Class<*> = type

    override fun deserialize(parser: JsonParser, context: DeserializationContext): R {
        val parts = OutcomeParts(context)
        OUTCOMES.getValue(QuestionKind.RATING).reader.read(parser, context, parts::accept)
        return type.cast(parts.rating(status))
    }
}
