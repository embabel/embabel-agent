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

import com.embabel.agent.decision.CallFailure
import com.embabel.agent.decision.DecisionKind
import com.embabel.agent.decision.DecisionProvenance
import com.embabel.agent.decision.DecisionSafeCode
import com.embabel.agent.decision.EvidenceKind
import com.embabel.agent.decision.KeyFailure
import com.embabel.agent.decision.PreparedDecisionRequest
import com.embabel.agent.decision.PreparedQuestion
import com.embabel.agent.decision.RawAnswer
import com.embabel.agent.decision.RawDecisionOutcome
import com.embabel.agent.decision.RawProbability
import com.embabel.common.util.EmbabelObjectMapperHolder
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.node.ObjectNode
import java.nio.charset.StandardCharsets

/** Strict System One codec. The private value tree preserves duplicate JSON property names. */
internal class JevWireCodec(
    private val mapperHolder: EmbabelObjectMapperHolder,
    private val requestedModel: String,
) {
    fun encode(request: PreparedDecisionRequest, model: String): ByteArray {
        val mapper = mapperHolder.get()
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

    private fun decodeEnvelope(bytes: ByteArray, request: PreparedDecisionRequest): RawDecisionOutcome {
        val root = try { parse(bytes) as? JsonValue.Obj } catch (_: Exception) { null }
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
        return RawDecisionOutcome.success(rawAnswers, DecisionProvenance.builder("typesafe", EvidenceKind.DISTRIBUTION)
            .requestedModel(requestedModel)
            .resolvedModel(model)
            .adapterVersion("jev-systemone-v1")
            .usage(inputTokens, outputTokens)
            .build())
    }

    private fun decodeAnswer(question: PreparedQuestion, node: JsonValue): RawAnswer {
        val answer = node as? JsonValue.Obj ?: return invalid(question)
        val type = answer.singleString("type") ?: return invalid(question)
        val expectedType = when (question.kind) {
            DecisionKind.YES_NO -> "noul"
            DecisionKind.CHOICE -> "choice"
            DecisionKind.RATING -> "score"
        }
        if (type !in setOf("noul", "choice", "score")) return RawAnswer.failure(question.id, KeyFailure.Unsupported, DecisionSafeCode.UNSUPPORTED)
        if (type != expectedType) return invalid(question)
        return when (question.kind) {
            DecisionKind.YES_NO -> answer.number("noul")?.takeIf { it.isFinite() && it in 0.0..1.0 }
                ?.let { RawAnswer.yesNo(question.id, it, null) } ?: invalid(question)
            DecisionKind.CHOICE -> choice(question, answer)
            DecisionKind.RATING -> rating(question, answer)
        }
    }

    private fun choice(question: PreparedQuestion, answer: JsonValue.Obj): RawAnswer {
        val choice = answer.singleString("choice") ?: return invalid(question)
        val confidence = answer.number("confidence") ?: return invalid(question)
        if (!confidence.isFinite() || confidence !in 0.0..1.0) return invalid(question)
        return distribution(question, answer.single("probabilities"), choice)
    }

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
        if (probabilities.entries.map { it.first }.distinct().size != probabilities.entries.size || probabilities.entries.size != question.support.size) return invalid(question)
        val indexed = probabilities.entries.map { (index, value) ->
            val parsed = index.toIntOrNull() ?: return invalid(question)
            if (index != parsed.toString()) return invalid(question)
            val probability = (value as? JsonValue.Num)?.value ?: return invalid(question)
            val support = question.support.getOrNull(parsed) ?: return invalid(question)
            parsed to RawProbability.of(support.id, probability)
        }
        val mapped = indexed.map { it.second }
        if (mapped.size != question.support.size || mapped.any { !it.probability.isFinite() || it.probability !in 0.0..1.0 }) return invalid(question)
        val expected = indexed.sumOf { (index, value) -> index * value.probability }
        if (kotlin.math.abs(expected - score) > 1e-9) return invalid(question)
        return RawAnswer.distribution(question.id, question.kind, mapped, null)
    }

    private fun distribution(question: PreparedQuestion, node: JsonValue?, selected: String?): RawAnswer {
        val probabilities = node as? JsonValue.Obj ?: return invalid(question)
        if (probabilities.entries.map { it.first }.distinct().size != probabilities.entries.size) return invalid(question)
        val mapped = probabilities.entries.map { (id, value) ->
            val probability = (value as? JsonValue.Num)?.value ?: return invalid(question)
            RawProbability.of(id, probability)
        }
        return RawAnswer.distribution(question.id, question.kind, mapped, selected)
    }

    private fun invalid(question: PreparedQuestion) = RawAnswer.failure(question.id, KeyFailure.Invalid, DecisionSafeCode.INVALID)
    private fun rejected() = RawDecisionOutcome.failure(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST)
    private fun safeProvenanceText(value: String) = value.isNotBlank() &&
        value.toByteArray(StandardCharsets.UTF_8).size <= 256 && value.none { it == '\n' || it == '\r' }

    private fun stateNode(state: Map<String, Any?>): ObjectNode = mapperHolder.get().createObjectNode().also { target ->
        state.forEach { (key, value) -> target.set(key, valueNode(value)) }
    }

    private fun valueNode(value: Any?): tools.jackson.databind.JsonNode = when (value) {
        null -> mapperHolder.get().nullNode()
        is String -> mapperHolder.get().getNodeFactory().textNode(value)
        is Boolean -> mapperHolder.get().getNodeFactory().booleanNode(value)
        is Int -> mapperHolder.get().getNodeFactory().numberNode(value)
        is Long -> mapperHolder.get().getNodeFactory().numberNode(value)
        is Double -> mapperHolder.get().getNodeFactory().numberNode(value)
        is Float -> mapperHolder.get().getNodeFactory().numberNode(value)
        is Number -> mapperHolder.get().getNodeFactory().numberNode(value.toDouble())
        is Map<*, *> -> stateNode(value.entries.associate { (key, nested) -> key as String to nested })
        is Iterable<*> -> mapperHolder.get().createArrayNode().also { array -> value.forEach { array.add(valueNode(it)) } }
        else -> error("prepared state must already be JSON-compatible")
    }

    private fun parse(bytes: ByteArray): JsonValue = mapperHolder.get().createParser(bytes).use { parser ->
        parser.nextToken() ?: throw IllegalArgumentException()
        val result = parseValue(parser)
        if (parser.nextToken() != null) throw IllegalArgumentException()
        result
    }

    private fun parseValue(parser: JsonParser): JsonValue = when (parser.currentToken()) {
        JsonToken.START_OBJECT -> JsonValue.Obj(buildList {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw IllegalArgumentException()
                val name = parser.currentName()
                parser.nextToken() ?: throw IllegalArgumentException()
                add(name to parseValue(parser))
            }
        })
        JsonToken.START_ARRAY -> JsonValue.Arr(buildList {
            while (parser.nextToken() != JsonToken.END_ARRAY) add(parseValue(parser))
        })
        JsonToken.VALUE_STRING -> JsonValue.Str(parser.text)
        JsonToken.VALUE_NUMBER_FLOAT, JsonToken.VALUE_NUMBER_INT -> JsonValue.Num(parser.doubleValue)
        JsonToken.VALUE_TRUE -> JsonValue.Bool(true)
        JsonToken.VALUE_FALSE -> JsonValue.Bool(false)
        JsonToken.VALUE_NULL -> JsonValue.Null
        else -> throw IllegalArgumentException()
    }

    private sealed interface JsonValue {
        class Obj(val entries: List<Pair<String, JsonValue>>) : JsonValue {
            fun single(name: String): JsonValue? = entries.filter { it.first == name }.singleOrNull()?.second
            fun singleString(name: String): String? = (single(name) as? Str)?.value
            fun number(name: String): Double? = (single(name) as? Num)?.value
            fun nonNegativeInt(name: String): Int? = number(name)?.let { value ->
                if (value.isFinite() && value >= 0 && value <= Int.MAX_VALUE && value == value.toInt().toDouble()) value.toInt() else null
            }
        }
        class Arr(val values: List<JsonValue>) : JsonValue
        class Str(val value: String) : JsonValue
        class Num(val value: Double) : JsonValue
        class Bool(val value: Boolean) : JsonValue
        data object Null : JsonValue
    }
}
