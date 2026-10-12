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
package com.embabel.common.ai.decision.cloudflare

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.*
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.spi.QuestionSetExecution
import com.embabel.common.ai.decision.spi.RatingAssessment
import com.embabel.common.ai.decision.systemone.*
import java.net.URI
import java.util.concurrent.CancellationException

internal class CloudflareDecisionService(
    override val name: String,
    private val options: CloudflareClientOptions,
    private val exchange: (URI, ByteArray) -> SystemOneHttpResponse,
) : DecisionService, QuestionSetExecution, PropositionAssessment, RatingAssessment {
    override val provider: String = CloudflareModelFactory.PROVIDER_NAME
    override fun capabilities(): DecisionCapabilities = SystemOneDecisionService.CAPABILITIES
    override fun askQuestionSet(request: DecisionRequest): DecisionResponse {
        val payload = try {
            CloudflareProfile.validateRequest(request)
            SystemOneCodec.encode(name.substringAfterLast('/'), request).also {
                require(it.size <= options.maxRequestBytes) { "Request exceeds byte limit" }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw UnsupportedDecisionException("Cloudflare does not support this decision request")
        }
        return try {
            val response = exchange(options.target(name), payload)
            val root = SystemOneCodec.read(response.body)
            require(root.isObject && root.get("success")?.isBoolean == true && root.get("success").booleanValue() &&
                root.get("errors")?.isArray == true && root.get("errors").isEmpty) { "Invalid Cloudflare REST envelope" }
            val result = root.get("result")
            require(result != null && result.isObject && !result.has("result")) { "Invalid Cloudflare result" }
            SystemOneCodec.decode(name, request, result, response.headers, CloudflareProfile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SystemOneCallFailure) {
            DecisionResponse.failed(request.spec, failure.reason)
        } catch (_: Exception) {
            DecisionResponse.failed(request.spec, FailureReason.INVALID_RESPONSE)
        }
    }
    override fun classify(request: ClassificationRequest): ClassificationResult = askQuestionSet(request).answer(request.spec.question)
    override fun assess(request: PropositionRequest): PropositionResult =
        assess(request.input, Questions.named("proposition").proposition(request.proposition).build())
    override fun assess(input: String, question: PropositionQuestionSpec): PropositionResult =
        askQuestionSet(DecisionRequest.of(input, question)).answer(question)
    override fun rate(input: String, question: RatingQuestionSpec): RatingResult =
        askQuestionSet(DecisionRequest.of(input, question)).answer(question)
}

internal object CloudflareProfile : SystemOneModelProfile {
    override fun validateRequest(request: DecisionRequest) {
        require(request.spec.questions.size in 1..64) { "Unsupported Cloudflare question count" }
        request.spec.questions.forEach { question ->
            require(question.instructions.isNotBlank()) { "Cloudflare instructions must be nonblank" }
            when (question) {
                is PropositionQuestionSpec -> Unit
                is ChoiceQuestionSpec -> require(question.options.size in 2..255) { "Unsupported Cloudflare choice count" }
                is RatingQuestionSpec -> require(question.levels.size in 2..10) { "Unsupported Cloudflare rating count" }
            }
        }
    }
    override fun provenance(metadata: SystemOneResponseMetadata): ModelProvenance {
        require(metadata.requestedModel in CloudflareModelFactory.SUPPORTED_MODELS &&
            (metadata.actualModel == metadata.requestedModel ||
                metadata.actualModel == metadata.requestedModel.substringAfterLast('/'))) { "Unexpected Cloudflare model identity" }
        return ModelProvenance(metadata.requestedModel, CloudflareModelFactory.PROVIDER_NAME)
    }
}
