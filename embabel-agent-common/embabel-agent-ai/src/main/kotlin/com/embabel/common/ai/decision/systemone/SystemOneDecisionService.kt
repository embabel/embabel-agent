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

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.classification.ModelProvenance
import com.embabel.common.ai.decision.*
import com.embabel.common.ai.decision.spi.DecisionResponseAssembler
import com.embabel.common.ai.decision.spi.PropositionAssessment
import com.embabel.common.ai.decision.spi.QuestionSetExecution
import com.embabel.common.ai.decision.spi.RatingAssessment
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.EnumSet
import java.util.concurrent.*
import java.util.concurrent.Flow
import java.util.function.Supplier
import kotlin.math.abs

internal class SystemOneDecisionService(
    override val name: String,
    override val provider: String,
    private val client: SystemOneClient,
    private val profile: SystemOneModelProfile,
) : DecisionService, QuestionSetExecution, PropositionAssessment, RatingAssessment {
    override fun capabilities(): DecisionCapabilities = CAPABILITIES
    override fun askQuestionSet(request: DecisionRequest): DecisionResponse = try {
        profile.validateRequest(request)
        val payload = SystemOneCodec.encode(name, request)
        val response = client.exchange("systemone", payload)
        SystemOneCodec.decode(name, request, response, profile)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SystemOneCallFailure) {
        DecisionResponse.failed(request.spec, failure.reason)
    } catch (_: Exception) {
        DecisionResponse.failed(request.spec, FailureReason.INVALID_RESPONSE)
    }
    override fun classify(request: ClassificationRequest): ClassificationResult = askQuestionSet(request).answer(request.spec.question)
    override fun assess(request: PropositionRequest): PropositionResult =
        assess(request.input, Questions.named("proposition").proposition(request.proposition).build())
    override fun assess(input: String, question: PropositionQuestionSpec): PropositionResult =
        askQuestionSet(DecisionRequest.of(input, question)).answer(question)
    override fun rate(input: String, question: RatingQuestionSpec): RatingResult =
        askQuestionSet(DecisionRequest.of(input, question)).answer(question)
    companion object {
        val CAPABILITIES: DecisionCapabilities = DecisionCapabilities.of(EnumSet.allOf(QuestionKind::class.java))
    }
}

internal object SystemOneCodec {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()
    fun read(body: ByteArray): JsonNode = mapper.readTree(body)
    fun text(node: JsonNode, field: String): String {
        val value = node.get(field)
        require(value != null && value.isString) { "Invalid SystemOne string field" }
        return value.stringValue()
    }
    private fun number(node: JsonNode, field: String): Double {
        val value = node.get(field)
        require(value != null && value.isNumber) { "Invalid SystemOne numeric field" }
        return value.doubleValue().also { require(it.isFinite()) { "Invalid SystemOne finite number" } }
    }
    private fun probability(node: JsonNode, field: String): Double = number(node, field).also {
        require(it in 0.0..1.0) { "Invalid SystemOne probability" }
    }
    private fun usage(node: JsonNode, field: String): Long {
        val value = node.get(field)
        require(value != null && value.isIntegralNumber && value.canConvertToLong() && value.longValue() >= 0) {
            "Invalid SystemOne token usage"
        }
        return value.longValue()
    }
    fun encode(model: String, request: DecisionRequest): ByteArray {
        val questions = LinkedHashMap<String, Any>()
        request.spec.questions.forEachIndexed { index, question ->
            val value = linkedMapOf<String, Any>("instructions" to question.instructions)
            when (question) {
                is PropositionQuestionSpec -> value["type"] = "noul"
                is ChoiceQuestionSpec -> { value["type"] = "choice"; value["criteria"] = question.options.associate { it.id to it.description } }
                is RatingQuestionSpec -> { value["type"] = "score"; value["criteria"] = descriptions(question) }
            }
            questions[key(index)] = value
        }
        return mapper.writeValueAsBytes(linkedMapOf("state" to request.input, "model" to model, "questions" to questions))
    }
    fun decode(model: String, request: DecisionRequest, response: SystemOneHttpResponse, profile: SystemOneModelProfile): DecisionResponse {
        val root = read(response.body)
        require(root.isObject) { "Invalid SystemOne envelope" }
        val actual = text(root, "model")
        val answers = root.get("answers")
        val usage = root.get("usage")
        require(answers != null && answers.isObject && usage != null && usage.isObject) { "Invalid SystemOne envelope" }
        val metadata = SystemOneResponseMetadata(model, actual, usage(usage, "input_tokens"), usage(usage, "output_tokens"), response.headers)
        val provenance = profile.provenance(metadata)
        require(validModelName(provenance.modelName) && validModelName(provenance.provider) &&
            (provenance.version == null || validModelName(provenance.version)) &&
            (provenance.requestId == null || provenance.requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))) { "Invalid SystemOne provenance" }
        val assembler = DecisionResponseAssembler.forSpec(request.spec, model)
        val known = request.spec.questions.indices.map(::key).toSet()
        answers.propertyNames().filter { it !in known }.forEach { assembler.unexpected() }
        request.spec.questions.forEachIndexed { index, question ->
            val answer = answers.get(key(index))
            if (answer != null) {
                try {
                    require(answer.isObject) { "Invalid SystemOne answer" }
                    when (question) {
                        is PropositionQuestionSpec -> {
                            require(text(answer, "type") == "noul") { "Wrong answer kind" }
                            val pTrue = probability(answer, "noul")
                            // Existing Inconclusive has no pTrue field; the exact-half value cannot be retained.
                            assembler.proposition(question.name, if (pTrue == 0.5) PropositionResult.Inconclusive(provenance)
                                else PropositionResult.Answered(pTrue > 0.5, provenance, pTrue))
                        }
                        is ChoiceQuestionSpec -> {
                            require(text(answer, "type") == "choice") { "Wrong answer kind" }
                            val selected = text(answer, "choice")
                            val ids = question.options.map { it.id }
                            val probabilities = distribution(answer.get("probabilities"), ids)
                            require(selected in ids && probabilities[ids.indexOf(selected)] == probabilities.max()) { "Invalid selected choice" }
                            val evidence = ChoiceDistribution.of(question.options, ids.zip(probabilities).map { (id, p) -> CategoryProbability(id, p) })
                            assembler.choice(question.name, ClassificationResult.Selected(selected, provenance, probability(answer, "confidence")), evidence)
                        }
                        is RatingQuestionSpec -> {
                            require(text(answer, "type") == "score") { "Wrong answer kind" }
                            val ids = question.levels.indices.map(Int::toString)
                            val legend = answer.get("legend")
                            require(legend != null && legend.isObject && legend.propertyNames().toSet() == ids.toSet()) { "Invalid score legend" }
                            require(ids.map { text(legend, it) } == descriptions(question)) { "Changed score legend" }
                            val probabilities = distribution(answer.get("probabilities"), ids)
                            val score = number(answer, "score")
                            require(abs(score - probabilities.mapIndexed { i, p -> i * p }.sum()) <= 1e-6) { "Invalid expected score" }
                            val evidence = question.levels.zip(probabilities).map { (level, p) -> LevelProbability(level.id, p) }
                            assembler.rating(question.name, RatingResult.Answered(provenance, null, evidence,
                                RatingScore(score, RatingStatistic.EXPECTED_LEVEL_INDEX), probability(answer, "confidence")))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    assembler.unreadable(question.name)
                }
            }
        }
        return assembler.build()
    }
    private fun distribution(node: JsonNode?, ids: List<String>): List<Double> {
        require(node != null && node.isObject && node.propertyNames().toSet() == ids.toSet()) { "Invalid probability domain" }
        return ids.map { probability(node, it) }.also {
            require(abs(it.sum() - 1.0) <= 1e-6) { "Invalid probability sum" }
        }
    }
    private fun descriptions(question: RatingQuestionSpec): List<String> = question.levels.map { it.description.ifBlank { it.id } }
    private fun key(index: Int): String = "q" + (index + 1).toString().padStart(3, '0')
}

internal class SystemOneCallFailure(val reason: FailureReason) : RuntimeException("SystemOne HTTP exchange failed", null, false, false)
internal class SystemOneHttpResponse(val body: ByteArray, val headers: Map<String, List<String>>)

/** JDK receiving allocation limits are separate; this client bounds retained decoded headers/body. */
internal class SystemOneClient(
    private val options: SystemOneClientOptions,
    private val tokenSupplier: Supplier<String?>,
    private val profile: SystemOneModelProfile,
) : AutoCloseable {
    private val client = HttpClient.newBuilder().connectTimeout(options.timeout).followRedirects(HttpClient.Redirect.NEVER).build()
    fun exchange(route: String, body: ByteArray?): SystemOneHttpResponse {
        if (Thread.currentThread().isInterrupted) throw CancellationException("SystemOne call interrupted")
        if (body != null && body.size > options.maxRequestBytes) throw SystemOneCallFailure(FailureReason.INVALID_RESPONSE)
        val token = try { tokenSupplier.get()?.takeIf { it.isNotBlank() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw SystemOneCallFailure(FailureReason.INVALID_RESPONSE) }
        if (token != null && (token.length > 4096 || token.any { it.isISOControl() } ||
                options.baseUrl.scheme.equals("http", true) && !loopback(options.baseUrl.host))) {
            throw SystemOneCallFailure(FailureReason.INVALID_RESPONSE)
        }
        val request = try {
            val builder = HttpRequest.newBuilder(options.endpoint(route)).timeout(options.timeout).header("Accept", "application/json")
            if (token != null) builder.header("Authorization", "Bearer $token")
            if (body == null) builder.GET() else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body))
            builder.build()
        } catch (_: Exception) { throw SystemOneCallFailure(FailureReason.INVALID_RESPONSE) }
        val future = client.sendAsync(request) { info ->
            val rejected = when {
                info.statusCode() != 200 -> try { profile.failureReason(info.statusCode()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { FailureReason.INVALID_RESPONSE }
                headerBytes(info.headers().map()) > 8192 -> FailureReason.INVALID_RESPONSE
                info.headers().firstValueAsLong("Content-Length").orElse(0) > options.maxResponseBytes -> FailureReason.INVALID_RESPONSE
                else -> null
            }
            LimitedBody(options.maxResponseBytes, rejected)
        }
        return try {
            val response = future.get(options.timeout.toNanos(), TimeUnit.NANOSECONDS)
            SystemOneHttpResponse(response.body(), response.headers().map())
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw CancellationException("SystemOne call interrupted").apply { initCause(InterruptedException()) }
        } catch (cancelled: CancellationException) {
            future.cancel(true)
            throw cancelled
        } catch (failure: Exception) {
            future.cancel(true)
            val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
            causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
            val reason = causes.filterIsInstance<SystemOneCallFailure>().firstOrNull()?.reason ?: FailureReason.UNAVAILABLE
            throw SystemOneCallFailure(reason)
        }
    }
    private fun loopback(host: String): Boolean {
        val value = host.removePrefix("[").removeSuffix("]")
        return value.equals("localhost", true) || value == "::1" ||
            value.matches(Regex("127(?:\\.[0-9]{1,3}){3}")) && runCatching { InetAddress.getByName(value).isLoopbackAddress }.getOrDefault(false)
    }
    override fun close() { client.shutdownNow() }

    private class LimitedBody(private val limit: Int, rejected: FailureReason?) : HttpResponse.BodySubscriber<ByteArray> {
        private val result = CompletableFuture<ByteArray>()
        private val output = ByteArrayOutputStream(minOf(limit, 4096))
        private var subscription: Flow.Subscription? = null
        init { if (rejected != null) result.completeExceptionally(SystemOneCallFailure(rejected)) }
        override fun getBody(): CompletionStage<ByteArray> = result
        override fun onSubscribe(value: Flow.Subscription) {
            subscription = value
            if (result.isDone) value.cancel() else value.request(1)
        }
        override fun onNext(buffers: List<ByteBuffer>) {
            if (result.isDone) return
            val incoming = buffers.sumOf { it.remaining().toLong() }
            if (incoming > limit - output.size()) {
                subscription?.cancel()
                result.completeExceptionally(SystemOneCallFailure(FailureReason.INVALID_RESPONSE))
                return
            }
            buffers.forEach { buffer -> val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); output.write(bytes) }
            subscription?.request(1)
        }
        override fun onError(error: Throwable) { result.completeExceptionally(SystemOneCallFailure(FailureReason.UNAVAILABLE)) }
        override fun onComplete() { result.complete(output.toByteArray()) }
    }
}
