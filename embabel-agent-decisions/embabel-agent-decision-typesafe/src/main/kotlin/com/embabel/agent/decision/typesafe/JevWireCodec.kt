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
package com.embabel.agent.decision.typesafe

import com.embabel.agent.decision.api.CallFailure
import com.embabel.agent.decision.api.DecisionKind
import com.embabel.agent.decision.api.DecisionProvenance
import com.embabel.agent.decision.api.DecisionSafeCode
import com.embabel.agent.decision.api.EvidenceKind
import com.embabel.agent.decision.api.KeyFailure
import com.embabel.agent.decision.api.PreparedDecisionRequest
import com.embabel.agent.decision.api.PreparedQuestion
import com.embabel.agent.decision.api.RawAnswer
import com.embabel.agent.decision.api.RawDecisionOutcome
import com.embabel.agent.decision.api.RawProbability
import com.embabel.common.util.EmbabelObjectMapperHolder
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets

/**
 * Maps prepared decisions to System One requests and responses to raw decision evidence.
 * YES_NO uses `noul`, CHOICE uses support ids, and RATING uses zero-based support indices.
 * Wire validation happens here; the shared facade validates the resulting distributions.
 */
internal class JevWireCodec(
    @Suppress("UNUSED_PARAMETER") mapperHolder: EmbabelObjectMapperHolder,
    private val requestedModel: String,
) {
    // Wire behavior must not inherit permissive parser features, serializers, or write flags from the application mapper.
    private val mapper: ObjectMapper = EmbabelObjectMapperHolder.createDefault().get()

    fun encode(request: PreparedDecisionRequest, model: String): ByteArray {
        val root = mapper.createObjectNode()
        root.set("state", stateNode(request.state))
        root.put("model", model)
        val questions = root.putObject("questions")
        request.questions.forEach { question ->
            val encoded = questions.putObject(question.id)
            when (question.kind) {
                DecisionKind.YES_NO -> {
                    encoded.put("type", "noul")
                    encoded.put("instructions", question.question)
                    encoded.putObject("criteria").put("true", "true").put("false", "false")
                }

                DecisionKind.CHOICE -> {
                    encoded.put("type", "choice")
                    encoded.put("instructions", question.question)
                    val criteria = encoded.putObject("criteria")
                    question.support.forEach { criteria.put(it.id, it.label) }
                }

                DecisionKind.RATING -> {
                    encoded.put("type", "score")
                    encoded.put("instructions", question.question)
                    val criteria = encoded.putArray("criteria")
                    question.support.forEach { criteria.add(it.label) }
                }
            }
        }
        return mapper.writeValueAsBytes(root)
    }

    fun decode(bytes: ByteArray, request: PreparedDecisionRequest): RawDecisionOutcome = try {
        decodeEnvelope(bytes, request)
    } catch (_: RuntimeException) {
        rejected()
    }

    /**
     * Rejects the whole response if its envelope is malformed or answer ids are duplicate or unknown.
     * Known answers are decoded independently so a key failure preserves valid siblings. Omitted
     * answers stay absent for the shared facade to report as missing.
     */
    private fun decodeEnvelope(bytes: ByteArray, request: PreparedDecisionRequest): RawDecisionOutcome {
        val root = try {
            parse(bytes) as? JsonValue.Obj
        } catch (_: Exception) {
            null
        }
            ?: return rejected()
        val model = root.singleString("model")?.takeIf(::safeProvenanceText) ?: return rejected()
        val usage = root.single("usage") as? JsonValue.Obj ?: return rejected()
        val inputTokens = usage.nonNegativeInt("input_tokens") ?: return rejected()
        val outputTokens = usage.nonNegativeInt("output_tokens") ?: return rejected()
        val answers = root.single("answers") as? JsonValue.Obj ?: return rejected()
        val answerIds = answers.entries.map { it.first }
        if (answerIds.distinct().size != answerIds.size || answerIds.any { id -> request.questions.none { it.id == id } }) return rejected()
        val rawAnswers = request.questions.mapNotNull { question ->
            answers.entries.firstOrNull { it.first == question.id }?.second?.let { decodeAnswer(question, it) }
        }
        return RawDecisionOutcome.success(
            rawAnswers, DecisionProvenance.builder("typesafe", EvidenceKind.DISTRIBUTION)
                .requestedModel(requestedModel)
                .resolvedModel(model)
                .adapterVersion("jev-systemone-v1")
                .usage(inputTokens, outputTokens)
                .build()
        )
    }

    private fun decodeAnswer(question: PreparedQuestion, node: JsonValue): RawAnswer {
        val answer = node as? JsonValue.Obj ?: return invalid(question)
        val type = answer.singleString("type") ?: return invalid(question)
        val expectedType = when (question.kind) {
            DecisionKind.YES_NO -> "noul"
            DecisionKind.CHOICE -> "choice"
            DecisionKind.RATING -> "score"
        }
        if (type !in setOf("noul", "choice", "score")) return RawAnswer.failure(
            question.id,
            KeyFailure.Unsupported,
            DecisionSafeCode.UNSUPPORTED
        )
        if (type != expectedType) return invalid(question)
        return when (question.kind) {
            DecisionKind.YES_NO -> answer.number("noul")?.takeIf { it.isFinite() && it in 0.0..1.0 }
                ?.let { RawAnswer.yesNo(question.id, it, null) } ?: invalid(question)

            DecisionKind.CHOICE -> choice(question, answer)
            DecisionKind.RATING -> rating(question, answer)
        }
    }

    /**
     * Requires a finite wire confidence in `0.0..1.0`, but uses the full distribution as evidence.
     * Confidence does not replace a probability or have to match the selected choice's probability.
     * The shared facade checks that the selected support id is a maximizer.
     */
    private fun choice(question: PreparedQuestion, answer: JsonValue.Obj): RawAnswer {
        val choice = answer.singleString("choice") ?: return invalid(question)
        val confidence = answer.number("confidence") ?: return invalid(question)
        if (!confidence.isFinite() || confidence !in 0.0..1.0) return invalid(question)
        return distribution(question, answer.single("probabilities"), choice)
    }

    /**
     * Checks the wire legend against labels in declared support order, then maps each zero-based
     * probability index back to its support id. JSON property order does not affect this mapping;
     * indices must use canonical integer spelling and cover every support exactly once.
     *
     * The wire score must equal the expected index, `sum(index * probability)`, within `1e-9`.
     * It is neither a selected level nor a score computed from domain-specific numeric anchors.
     * Confidence is checked for finiteness and range but is not used as distribution evidence.
     * No selected id is supplied, leaving selection and ties to the shared facade.
     */
    private fun rating(question: PreparedQuestion, answer: JsonValue.Obj): RawAnswer {
        val score = answer.number("score") ?: return invalid(question)
        val confidence = answer.number("confidence") ?: return invalid(question)
        if (!score.isFinite() || !confidence.isFinite() || confidence !in 0.0..1.0) return invalid(question)
        val legend = answer.single("legend") as? JsonValue.Obj ?: return invalid(question)
        if (legend.entries.map { it.first }.distinct().size != legend.entries.size) return invalid(question)
        if (legend.entries.size != question.support.size || legend.entries.any { (index, value) ->
                val parsed = index.toIntOrNull() ?: return@any true
                index != parsed.toString() || question.support.getOrNull(parsed)?.label != (value as? JsonValue.Str)?.value
            }) return invalid(question)
        val probabilities = answer.single("probabilities") as? JsonValue.Obj ?: return invalid(question)
        if (probabilities.entries.map { it.first }
                .distinct().size != probabilities.entries.size || probabilities.entries.size != question.support.size) return invalid(
            question
        )
        val indexed = probabilities.entries.map { (index, value) ->
            val parsed = index.toIntOrNull() ?: return invalid(question)
            if (index != parsed.toString()) return invalid(question)
            val probability = (value as? JsonValue.Num)?.value ?: return invalid(question)
            val support = question.support.getOrNull(parsed) ?: return invalid(question)
            parsed to RawProbability.of(support.id, probability)
        }
        val mapped = indexed.map { it.second }
        if (mapped.size != question.support.size || mapped.any { !it.probability.isFinite() || it.probability !in 0.0..1.0 }) return invalid(
            question
        )
        val expected = indexed.sumOf { (index, value) -> index * value.probability }
        if (kotlin.math.abs(expected - score) > 1e-9) return invalid(question)
        return RawAnswer.distribution(question.id, question.kind, mapped, null)
    }

    /**
     * Preserves support ids and numeric probabilities for shared facade validation. The facade
     * requires every declared support exactly once, finite probabilities in `0.0..1.0`, a sum of
     * one within `1e-9`, and any selected id to be a maximizer. It resolves ties in support order.
     */
    private fun distribution(question: PreparedQuestion, node: JsonValue?, selected: String?): RawAnswer {
        val probabilities = node as? JsonValue.Obj ?: return invalid(question)
        if (probabilities.entries.map { it.first }.distinct().size != probabilities.entries.size) return invalid(
            question
        )
        val mapped = probabilities.entries.map { (id, value) ->
            val probability = (value as? JsonValue.Num)?.value ?: return invalid(question)
            RawProbability.of(id, probability)
        }
        return RawAnswer.distribution(question.id, question.kind, mapped, selected)
    }

    private fun invalid(question: PreparedQuestion) =
        RawAnswer.failure(question.id, KeyFailure.Invalid, DecisionSafeCode.INVALID)

    private fun rejected() = RawDecisionOutcome.failure(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST)
    private fun safeProvenanceText(value: String) = value.isNotBlank() &&
            value.toByteArray(StandardCharsets.UTF_8).size <= 256 && value.none { it == '\n' || it == '\r' }

    private fun stateNode(state: Map<String, Any?>): ObjectNode = mapper.createObjectNode().also { target ->
        state.forEach { (key, value) -> target.set(key, valueNode(value)) }
    }

    private fun valueNode(value: Any?): tools.jackson.databind.JsonNode = when (value) {
        null -> mapper.nullNode()
        is String -> mapper.nodeFactory.stringNode(value)
        is Boolean -> mapper.nodeFactory.booleanNode(value)
        is Byte -> mapper.nodeFactory.numberNode(value)
        is Short -> mapper.nodeFactory.numberNode(value)
        is Int -> mapper.nodeFactory.numberNode(value)
        is Long -> mapper.nodeFactory.numberNode(value)
        is BigInteger -> mapper.nodeFactory.numberNode(value)
        is BigDecimal -> mapper.nodeFactory.numberNode(value)
        is Double -> mapper.nodeFactory.numberNode(value.also { require(it.isFinite()) { "state numbers must be finite" } })
        is Float -> mapper.nodeFactory.numberNode(value.also { require(it.isFinite()) { "state numbers must be finite" } })
        is Number -> error("prepared state contains an unsupported number type")
        is Map<*, *> -> stateNode(value.entries.associate { (key, nested) -> key as String to nested })
        is Iterable<*> -> mapper.createArrayNode().also { array -> value.forEach { array.add(valueNode(it)) } }
        else -> error("prepared state must already be JSON-compatible")
    }

    /**
     * Reads exactly one JSON value into a tree that retains duplicate object properties. Collapsing
     * them into a map would hide malformed fields from the envelope and per-answer checks.
     */
    private fun parse(bytes: ByteArray): JsonValue = mapper.createParser(bytes).use { parser ->
        parser.nextToken() ?: throw IllegalArgumentException()
        val result = parseValue(parser)
        require(parser.nextToken() == null)
        result
    }

    private fun parseValue(parser: JsonParser): JsonValue = when (parser.currentToken()) {
        JsonToken.START_OBJECT -> JsonValue.Obj(buildList {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.PROPERTY_NAME)
                val name = parser.currentName()
                parser.nextToken() ?: throw IllegalArgumentException()
                add(name to parseValue(parser))
            }
        })

        JsonToken.START_ARRAY -> JsonValue.Arr(buildList {
            while (parser.nextToken() != JsonToken.END_ARRAY) add(parseValue(parser))
        })

        JsonToken.VALUE_STRING -> JsonValue.Str(parser.string)
        JsonToken.VALUE_NUMBER_FLOAT, JsonToken.VALUE_NUMBER_INT -> JsonValue.Num(parser.doubleValue)
        JsonToken.VALUE_TRUE -> JsonValue.Bool(true)
        JsonToken.VALUE_FALSE -> JsonValue.Bool(false)
        JsonToken.VALUE_NULL -> JsonValue.Null
        else -> throw IllegalArgumentException()
    }

    private sealed interface JsonValue {
        class Obj(val entries: List<Pair<String, JsonValue>>) : JsonValue {
            fun single(name: String): JsonValue? = entries.singleOrNull { it.first == name }?.second
            fun singleString(name: String): String? = (single(name) as? Str)?.value
            fun number(name: String): Double? = (single(name) as? Num)?.value
            fun nonNegativeInt(name: String): Int? = number(name)?.let { value ->
                if (value.isFinite() && value >= 0 && value <= Int.MAX_VALUE && value == value.toInt()
                        .toDouble()
                ) value.toInt() else null
            }
        }

        class Arr(val values: List<JsonValue>) : JsonValue
        class Str(val value: String) : JsonValue
        class Num(val value: Double) : JsonValue
        class Bool(val value: Boolean) : JsonValue
        data object Null : JsonValue
    }
}
