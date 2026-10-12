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
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.MessageConstraintException
import org.apache.hc.core5.http.config.Http1Config
import org.apache.hc.core5.http.io.entity.ByteArrayEntity
import org.apache.hc.core5.io.CloseMode
import org.apache.hc.core5.io.ModalCloseable
import org.apache.hc.core5.util.Timeout
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.EnumSet
import java.util.concurrent.*
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

/** Transport receiving limits are separate; this client bounds retained decoded headers/body. */
internal class SystemOneClient(
    private val options: SystemOneClientOptions,
    private val tokenSupplier: Supplier<String?>,
    private val profile: SystemOneModelProfile,
) : AutoCloseable {
    private val timeout = Timeout.of(options.timeout)
    // Receiving constraints bound parsing independently of the accepted 8KiB metadata budget.
    private val connections = PoolingHttpClientConnectionManagerBuilder.create()
        .setConnectionFactory(ManagedHttpClientConnectionFactory.builder().http1Config(
            Http1Config.custom().setMaxHeaderCount(64).setMaxLineLength(16384).build()).build())
        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build())
        .build()
    private val client = HttpClients.custom().setConnectionManager(connections)
        .disableAutomaticRetries().disableRedirectHandling().disableContentCompression().disableCookieManagement()
        .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(timeout)
            .setResponseTimeout(timeout).setHardCancellationEnabled(true).build()).build()
    // One lazy daemon watchdog per factory; exchanges execute on their callers, without I/O workers.
    private val watchdog = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "systemone-http-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun exchange(route: String, body: ByteArray?): SystemOneHttpResponse {
        val caller = Thread.currentThread()
        if (caller.isInterrupted) throw CancellationException("SystemOne call interrupted")
        if (body != null && body.size > options.maxRequestBytes) throw SystemOneCallFailure(FailureReason.INVALID_RESPONSE)
        val token = try { tokenSupplier.get()?.takeIf { it.isNotBlank() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw SystemOneCallFailure(FailureReason.INVALID_RESPONSE) }
        if (token != null && (token.length > 4096 || token.any { it.isISOControl() } ||
                options.baseUrl.scheme.equals("http", true) && !loopback(options.baseUrl.host))) {
            throw SystemOneCallFailure(FailureReason.INVALID_RESPONSE)
        }
        val request = try {
            HttpUriRequestBase(if (body == null) "GET" else "POST", options.endpoint(route)).apply {
                setHeader("Accept", "application/json")
                if (token != null) setHeader("Authorization", "Bearer $token")
                if (body != null) entity = ByteArrayEntity(body, ContentType.APPLICATION_JSON)
            }
        } catch (_: Exception) { throw SystemOneCallFailure(FailureReason.INVALID_RESPONSE) }
        val start = System.nanoTime()
        fun checkCancelled() {
            if (caller.isInterrupted) throw CancellationException("SystemOne call interrupted")
            if (System.nanoTime() - start >= options.timeout.toNanos()) throw SystemOneCallFailure(FailureReason.UNAVAILABLE)
        }
        var deadline: ScheduledFuture<*>? = null
        var interruption: ScheduledFuture<*>? = null
        var response: ClassicHttpResponse? = null
        return try {
            deadline = watchdog.schedule({ request.cancel() }, options.timeout.toNanos(), TimeUnit.NANOSECONDS)
            interruption = watchdog.scheduleWithFixedDelay({ if (caller.isInterrupted) request.cancel() },
                0, 5, TimeUnit.MILLISECONDS)
            checkCancelled()
            response = client.executeOpen(null, request, null)
            checkCancelled()
            if (response.code != 200) {
                val reason = try { profile.failureReason(response.code) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { FailureReason.INVALID_RESPONSE }
                throw SystemOneCallFailure(reason)
            }
            val headers = response.headers.groupBy({ it.name.lowercase(java.util.Locale.ROOT) }, { it.value })
            if (headerBytes(headers) > 8192 || (response.entity?.contentLength ?: 0) > options.maxResponseBytes) {
                throw SystemOneCallFailure(FailureReason.INVALID_RESPONSE)
            }
            val output = ByteArrayOutputStream(minOf(options.maxResponseBytes, 4096))
            val input = response.entity?.content
            if (input != null) {
                val buffer = ByteArray(minOf(options.maxResponseBytes + 1, 4096))
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer, 0, minOf(buffer.size, options.maxResponseBytes - output.size() + 1))
                    if (count < 0) break
                    if (count > options.maxResponseBytes - output.size()) throw SystemOneCallFailure(FailureReason.INVALID_RESPONSE)
                    output.write(buffer, 0, count)
                }
            }
            checkCancelled()
            SystemOneHttpResponse(output.toByteArray(), headers)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            checkCancelled()
            val reason = when (failure) {
                is SystemOneCallFailure -> failure.reason
                is MessageConstraintException -> FailureReason.INVALID_RESPONSE
                else -> FailureReason.UNAVAILABLE
            }
            throw SystemOneCallFailure(reason)
        } finally {
            // Abort before closing: graceful entity/response closure may drain an unbounded rejected body.
            request.cancel()
            (response as? ModalCloseable)?.close(CloseMode.IMMEDIATE)
            deadline?.cancel(false)
            interruption?.cancel(false)
        }
    }

    private fun loopback(host: String): Boolean {
        val value = host.removePrefix("[").removeSuffix("]")
        return value.equals("localhost", true) || value == "::1" ||
            value.matches(Regex("127(?:\\.[0-9]{1,3}){3}")) && runCatching { InetAddress.getByName(value).isLoopbackAddress }.getOrDefault(false)
    }

    override fun close() {
        client.close(CloseMode.IMMEDIATE)
        watchdog.shutdownNow()
    }
}
