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
package com.embabel.agent.systemone

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.Questions
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.UnsupportedDecisionException
import com.embabel.common.ai.decision.spi.DecisionContentCapture
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.spi.QuestionSetExecution
import com.embabel.common.ai.decision.spi.RatingAssessment
import com.embabel.common.ai.decision.systemone.SystemOneCodec
import com.embabel.common.ai.decision.systemone.SystemOneFormatException
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * One model on a System One endpoint. Every call is one question set sent in one request. A whole
 * spec goes out together, and a single classification, proposition or rating goes out as a set of one.
 * Provider-limit rejections throw [UnsupportedDecisionException] before credentials or HTTP are touched.
 *
 * A failed call returns failure outcomes and logs one WARN line with fixed fields: service,
 * provider, operation, cause, status class, attempts, elapsed time and request id. Inputs,
 * bodies, keys and endpoints never appear in it. A response whose answers can't be turned into
 * results fails the same way, as an invalid response.
 *
 * Provenance names the model the response reports when that name is up to 128 letters, digits
 * and `. _ : / @ + -`. Otherwise it names the requested model, so response text can't reach it.
 */
internal class SystemOneDecisionService(
    override val name: String,
    private val transport: SystemOneTransport,
) : DecisionService, QuestionSetExecution, PropositionAssessment, RatingAssessment {

    override val provider: String get() = transport.endpoint.provider

    // Every question kind is supported; the binding checks provider-specific request limits.
    override fun capabilities(): DecisionCapabilities = CAPABILITIES

    override fun askQuestionSet(request: DecisionRequest): DecisionResponse = run(request, Operation.ASK)

    override fun classify(request: ClassificationRequest): ClassificationResult =
        run(request, Operation.CLASSIFY).answer(request.spec.question)

    override fun assess(request: PropositionRequest): PropositionResult {
        val question = Questions.named(PROPOSITION).proposition(request.proposition).build()
        return run(DecisionRequest.of(request.input, question), Operation.ASSESS).answer(question)
    }

    override fun assess(input: String, question: PropositionQuestionSpec): PropositionResult =
        run(DecisionRequest.of(input, question), Operation.ASSESS).answer(question)

    override fun rate(input: String, question: RatingQuestionSpec): RatingResult =
        run(DecisionRequest.of(input, question), Operation.RATE).answer(question)

    /**
     * Sends the request's question set and decodes the reply. A failed call, or a reply that can't
     * be turned into results, returns failure outcomes.
     *
     * @param request the input and questions
     * @param operation which public call this is, for logs
     * @return the answers, or failure outcomes
     * @throws UnsupportedDecisionException if the binding rejects provider-specific request limits
     */
    private fun run(request: DecisionRequest, operation: Operation): DecisionResponse {
        val binding = transport.endpoint.binding
        binding.check(name, request)
        val started = System.nanoTime()
        val reply = try {
            transport.decide(name, binding.decisionRequest(name, request))
        } catch (failure: SystemOneFailure) {
            return failed(request, operation, failure, started)
        }
        return try {
            val response = binding.decisionResponse(reply.body)
            trace(operation, response)
            SystemOneCodec.decode(response, request.spec, name) { model ->
                ModelProvenance(model?.takeIf(MODEL::matches) ?: name, provider, null, reply.requestId)
            }
        } catch (_: SystemOneFormatException) {
            failed(request, operation, SystemOneFailure.invalidResponse(reply.status, reply.requestId), started)
        } catch (_: IllegalArgumentException) {
            // A result type rejected the answers. Its message can quote them, so it is dropped.
            failed(request, operation, SystemOneFailure.invalidResponse(reply.status, reply.requestId), started)
        }
    }

    /**
     * Logs the failure's WARN line and returns failure outcomes for every question.
     *
     * @param request the request that failed
     * @param operation which public call this is
     * @param failure what went wrong
     * @param started when the call started, from [System.nanoTime]
     * @return failure outcomes for the request's questions
     */
    private fun failed(
        request: DecisionRequest,
        operation: Operation,
        failure: SystemOneFailure,
        started: Long,
    ): DecisionResponse {
        logger.warn(
            "System One {} failed with reason {}: service={}, provider={}, operation={}, cause={}, status={}, " +
                "attempts=1, elapsedMs={}, requestId={}",
            operation.label,
            failure.reason,
            name,
            provider,
            operation.id,
            failure.category,
            failure.statusFamily,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            failure.requestId ?: "none",
        )
        return DecisionResponse.failed(request.spec, failure.reason)
    }

    /**
     * Logs the unwrapped System One response at TRACE. The response is provider output that can
     * echo the input, so it is only logged when content capture is on. A binding's envelope is never
     * logged, since its error text can echo the input too.
     *
     * @param operation which public call this is
     * @param response the System One response the binding unwrapped
     */
    private fun trace(operation: Operation, response: ByteArray) {
        if (DecisionContentCapture.isEnabled() && logger.isTraceEnabled) {
            logger.trace(
                "System One response content: service={}, operation={}, response={}",
                name,
                operation.id,
                String(response, Charsets.UTF_8),
            )
        }
    }

    override fun toString(): String = "SystemOneDecisionService(name=$name, provider=$provider)"

    private enum class Operation(val id: String, val label: String) {
        ASK("ask_question_set", "question set"),
        CLASSIFY("classify", "classification"),
        ASSESS("assess", "proposition assessment"),
        RATE("rate", "rating"),
    }

    private companion object {
        const val PROPOSITION = "proposition"
        val MODEL = Regex("[A-Za-z0-9._:/@+-]{1,128}")
        val CAPABILITIES: DecisionCapabilities = DecisionCapabilities.of(QuestionKind.entries.toSet())
        val logger = LoggerFactory.getLogger(SystemOneDecisionService::class.java)
    }
}
