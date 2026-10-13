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
package com.embabel.common.ai.decision.systemone

import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionSpec
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.Question
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.spi.DecisionResponseAssembler
import org.jetbrains.annotations.ApiStatus
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.util.function.Function

/**
 * Reads and writes the System One wire format, the JSON protocol for asking a decision model
 * several typed questions about one piece of state in a single request.
 *
 * A request is `{"state", "model", "questions"}`. Questions are keyed `q1` to `qN` in spec order,
 * so the caller's question names never leave the process. A proposition goes out as a `noul`
 * question, a choice as a `choice` question with its options as criteria, and a rating as a
 * `score` question with its level descriptions as an ordered criteria list.
 *
 * A response is `{"model", "answers", "usage"}`. The envelope is read strictly: anything that is
 * not one JSON object with a non-empty `answers` object, a repeated member, or trailing content
 * throws [SystemOneFormatException] and fails the whole request. Each answer is then read on its
 * own, so one bad answer fails only its question. Members this codec doesn't know are ignored, so
 * a newer model can add fields. The evidence rules for each answer type are in [SystemOneAnswers].
 */
@ApiStatus.Experimental
object SystemOneCodec {

    /** The path that answers a question set. */
    const val DECISION_PATH = "/v1/systemone"

    /** The path that lists the models an endpoint serves. */
    const val MODELS_PATH = "/v1/models"

    internal val TYPES: Map<QuestionKind, String> = mapOf(
        QuestionKind.PROPOSITION to "noul",
        QuestionKind.CHOICE to "choice",
        QuestionKind.RATING to "score",
    )

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    /**
     * Writes the request body for one decision request.
     *
     * @param model the model to ask
     * @param request the input and the questions to ask about it
     * @return the JSON body as UTF-8 bytes
     */
    @JvmStatic
    fun encode(model: String, request: DecisionRequest): ByteArray {
        val body = mapper.createObjectNode().put("state", request.input).put("model", model)
        val questions = body.putObject("questions")
        request.spec.questions.forEachIndexed { index, question -> writeQuestion(questions.putObject(key(index)), question) }
        return mapper.writeValueAsBytes(body)
    }

    /**
     * Reads a response body into a decision response for the spec that was asked.
     *
     * @param body the response body
     * @param spec the spec the request was encoded from
     * @param serviceName the answering service's name, used in the anomaly log line
     * @param provenance builds the provenance from the model the response names, or from null when
     * it names none
     * @return one answer per question in spec order
     * @throws SystemOneFormatException if the envelope can't be read
     */
    @JvmStatic
    fun decode(
        body: ByteArray,
        spec: DecisionSpec,
        serviceName: String,
        provenance: Function<String?, ModelProvenance>,
    ): DecisionResponse {
        val root = readObject(body)
        val answers = root.get("answers")
        if (answers == null || !answers.isObject || answers.size() == 0) {
            throw SystemOneFormatException("The response has no answers")
        }
        val answeredBy = provenance.apply(text(root.get("model")))
        val questions = spec.questions.withIndex().associate { (index, question) -> key(index) to question }
        val assembler = DecisionResponseAssembler.forSpec(spec, serviceName)
        answers.properties().forEach { (key, answer) ->
            val question = questions[key]
            if (question == null) assembler.unexpected() else SystemOneAnswers.read(assembler, question, answer, answeredBy)
        }
        return assembler.build()
    }

    /**
     * Reads the model names from a model listing, `{"models": [{"name", ...}]}`.
     *
     * @param body the listing body
     * @return the model names in the order listed
     * @throws SystemOneFormatException if the listing can't be read or a model has no name
     */
    @JvmStatic
    fun modelNames(body: ByteArray): List<String> {
        val models = readObject(body).get("models")
        if (models == null || !models.isArray) throw SystemOneFormatException("The model listing has no models")
        return models.values().map { text(it.get("name")) ?: throw SystemOneFormatException("A listed model has no name") }
    }

    private fun key(index: Int) = "q${index + 1}"

    private fun writeQuestion(node: ObjectNode, question: Question<*>) {
        node.put("type", TYPES.getValue(question.kind)).put("instructions", question.instructions)
        when (question) {
            is PropositionQuestionSpec -> Unit
            is ChoiceQuestionSpec -> {
                val criteria = node.putObject("criteria")
                question.options.forEach { criteria.put(it.id, it.description) }
            }
            is RatingQuestionSpec -> {
                val criteria = node.putArray("criteria")
                question.levels.forEach { criteria.add(it.description.ifBlank { it.id }) }
            }
        }
    }

    private fun readObject(body: ByteArray): JsonNode {
        val root = try {
            mapper.readTree(body)
        } catch (_: JacksonException) {
            // The parser's message can quote the body, which a model can be steered to fill, so it is dropped.
            throw SystemOneFormatException("The response is not valid JSON")
        }
        if (root == null || !root.isObject) throw SystemOneFormatException("The response is not a JSON object")
        return root
    }

    internal fun text(node: JsonNode?): String? =
        node?.takeIf { it.isString }?.stringValue()?.takeIf { it.isNotBlank() }
}
