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
package com.embabel.agent.cloudflare

import com.embabel.agent.systemone.SystemOneBinding
import com.embabel.agent.systemone.SystemOneEndpoint
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.Question
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.ai.decision.systemone.SystemOneCodec
import com.embabel.common.ai.decision.systemone.SystemOneFormatException
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.util.RawValue
import java.net.URI

/**
 * How Workers AI exposes System One. Cloudflare hosts some decision models itself and runs others
 * from their makers.
 *
 * A Cloudflare-hosted model, such as `@cf/cloudflare/clef`, runs at `/ai/run/<model id>` under the
 * account, and the body names it by its short name, such as `clef`. These are the CLEF models, whose
 * input schema takes 1 to 64 questions, 2 to 255 options per choice and 2 to 10 levels per rating,
 * so a request outside those limits is refused before anything is sent.
 * Hosted ids must name System One-compatible models; id validation checks only their format.
 *
 * A model from another maker, such as `typesafe/jev`, runs at `/ai/run` under the account. The body
 * names the model and carries the System One request as its `input`, without a model of its own.
 *
 * Every response comes inside Cloudflare's `{success, errors, messages, result}` envelope. Models
 * are listed by searching the account's catalog for `clef`, which finds the Cloudflare-hosted ones.
 */
internal object CloudflareBinding : SystemOneBinding {

    private const val MAX_QUESTIONS = 64
    private const val HOSTED = "@cf/"
    private val OPTIONS = 2..255
    private val LEVELS = 2..10
    private val HOSTED_MODEL = Regex("@cf/[a-z0-9-]+/[a-z0-9][a-z0-9.-]*")
    private val THIRD_PARTY_MODEL = Regex("[a-z0-9-]+/[a-z0-9][a-z0-9.-]*")

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    override fun validateModel(model: String) = requireModelId(model)

    override fun decisionUri(endpoint: SystemOneEndpoint, model: String): URI {
        requireModelId(model)
        return endpoint.resolve(if (model.startsWith(HOSTED)) "/ai/run/$model" else "/ai/run")
    }

    override fun decisionRequest(model: String, request: DecisionRequest): ByteArray {
        if (model.startsWith(HOSTED)) {
            return SystemOneCodec.encode(model.substringAfterLast('/'), request)
        }
        // The encoded request goes in as written, since reading it back could hit the parser's size limits.
        val body = mapper.createObjectNode().put("model", model)
        body.putRawValue("input", RawValue(SystemOneCodec.encode(request).decodeToString()))
        return mapper.writeValueAsBytes(body)
    }

    override fun check(model: String, request: DecisionRequest) {
        if (!model.startsWith(HOSTED)) return
        val questions = request.spec.questions
        if (questions.size > MAX_QUESTIONS) {
            throw UnsupportedDecisionException(
                "Cloudflare CLEF takes at most $MAX_QUESTIONS questions per request, and this request has ${questions.size}. " +
                    "Split the spec into smaller requests.",
            )
        }
        val unsupportedQuestions = questions.filterNot(::fits)
        if (unsupportedQuestions.isNotEmpty()) {
            throw UnsupportedDecisionException(
                "Cloudflare CLEF takes 2 to 255 options per choice and 2 to 10 levels per rating. Outside those limits: " +
                    unsupportedQuestions.joinToString { "'${it.name}'" } + ".",
            )
        }
    }

    override fun decisionResponse(body: ByteArray): ByteArray {
        val result = result(body)
        if (!result.isObject) throw SystemOneFormatException("The Cloudflare result is not a System One response")
        return mapper.writeValueAsBytes(result)
    }

    override fun modelsUri(endpoint: SystemOneEndpoint): URI = endpoint.resolve("/ai/models/search?search=clef")

    override fun modelNames(body: ByteArray): List<String> {
        val result = result(body)
        if (!result.isArray) throw SystemOneFormatException("The Cloudflare model search has no results")
        return result.values().mapNotNull { model -> model.get("name")?.takeIf { it.isString }?.stringValue() }
    }

    override fun toString(): String = "CloudflareBinding"

    /**
     * Checks that a model id has the shape Workers AI uses. A hosted model id goes into the path,
     * so anything that could change the path is refused. [decisionUri] checks again, so a service
     * built some other way still never sends a malformed id.
     *
     * @param model the model id
     * @throws IllegalArgumentException if the id is not `@cf/<author>/<model>` or `<author>/<model>`
     */
    private fun requireModelId(model: String) {
        if (model.startsWith(HOSTED)) {
            require(HOSTED_MODEL.matches(model)) { "A Workers AI model id looks like @cf/<author>/<model>" }
        } else {
            require(THIRD_PARTY_MODEL.matches(model)) { "A third-party Workers AI model id looks like <author>/<model>" }
        }
    }

    /**
     * Tells whether a question fits the CLEF schema's limits.
     *
     * @param question the question
     * @return true when the question has an option or level count CLEF takes
     */
    private fun fits(question: Question<*>): Boolean = when (question) {
        is PropositionQuestionSpec -> true
        is ChoiceQuestionSpec -> question.options.size in OPTIONS
        is RatingQuestionSpec -> question.levels.size in LEVELS
    }

    /**
     * Unwraps the `result` of a successful envelope. A successful call has success true, no errors
     * and a result. Cloudflare's error text is never kept, since it can echo the input.
     *
     * @param body the response body
     * @return the envelope's result
     * @throws SystemOneFormatException if the body is not valid JSON, reports a failure or has no result
     */
    private fun result(body: ByteArray): JsonNode {
        val root = try {
            mapper.readTree(body)
        } catch (_: JacksonException) {
            throw SystemOneFormatException("The Cloudflare response is not valid JSON")
        }
        if (root == null || !root.isObject) {
            throw SystemOneFormatException("The Cloudflare response reports a failure")
        }
        val success = root.get("success")
        if (success == null || !success.isBoolean || !success.booleanValue()) {
            throw SystemOneFormatException("The Cloudflare response reports a failure")
        }
        val errors = root.get("errors")
        if (errors == null || !errors.isArray || errors.size() > 0) {
            throw SystemOneFormatException("The Cloudflare response reports a failure")
        }
        return root.get("result") ?: throw SystemOneFormatException("The Cloudflare response has no result")
    }
}
