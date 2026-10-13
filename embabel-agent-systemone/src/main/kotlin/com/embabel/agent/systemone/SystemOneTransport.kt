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

import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.decision.systemone.SystemOneCodec
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

/**
 * Sends System One calls over HTTP and hands back the raw body, or throws a [SystemOneFailure].
 *
 * - The key is resolved on every call. A blank key sends no Authorization header, even when the
 *   application's client would add one. A key that can't be resolved or holds control characters
 *   fails the call before anything is sent.
 * - Error bodies are never read, and a body over the endpoint's limit is rejected from its
 *   declared length or after one byte past the limit. Response buffering is turned off on the
 *   client, and the stream is closed before the response, so a rejected body is never drained.
 * - Each call is observed as `embabel.systemone.request`, tagged with provider, operation,
 *   outcome and status family. A failing observation handler never changes the result or leaves
 *   its scope open. There are no retries.
 * - A caller that is already interrupted gets a [CancellationException] before anything is sent,
 *   and a call that fails because of an interrupt is cancelled too. The interrupt flag stays set.
 */
internal class SystemOneTransport(
    val endpoint: SystemOneEndpoint,
    private val apiKey: Supplier<String?>,
    builder: RestClient.Builder?,
    private val observations: ObservationRegistry,
) {

    /** A successful reply. */
    class Reply(val body: ByteArray, val status: Int, val requestId: String?)

    /** `application` when the caller supplied the HTTP client, `fallback` when this transport built it. */
    val kind: String = if (builder == null) "fallback" else "application"

    private val restClient: RestClient = (builder?.clone() ?: RestClient.builder().requestFactory(fallback()))
        .bufferContent { _, _ -> false }
        .defaultHeaders { it.remove(HttpHeaders.AUTHORIZATION) }
        .build()

    fun decide(body: ByteArray): Reply = call(OPERATION_DECIDE) {
        restClient.post().uri(endpoint.resolve(SystemOneCodec.DECISION_PATH))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .headers { authorize(it, apiKey) }
            .body(body)
    }

    /**
     * Lists the endpoint's models.
     *
     * @param key supplies the key for this call, the transport's own supplier by default
     */
    fun listModels(key: Supplier<String?> = apiKey): Reply = call(OPERATION_MODELS) {
        restClient.get().uri(endpoint.resolve(SystemOneCodec.MODELS_PATH))
            .accept(MediaType.APPLICATION_JSON)
            .headers { authorize(it, key) }
    }

    /**
     * Reads a key from the supplier. A blank key is null.
     *
     * @throws SystemOneFailure if the supplier fails or the key holds control characters
     * @throws CancellationException if the supplier was interrupted
     */
    fun resolveKey(supplier: Supplier<String?>): String? {
        val key = try {
            supplier.get()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            if (failure.isInterruption()) throw interrupted()
            throw SystemOneFailure.unavailable(SystemOneFailure.CREDENTIAL)
        }
        if (key.isNullOrBlank()) return null
        if (key.any(Char::isISOControl)) throw SystemOneFailure.unavailable(SystemOneFailure.CREDENTIAL)
        return key.trim()
    }

    private fun call(operation: String, request: () -> RestClient.RequestHeadersSpec<*>): Reply {
        rejectIfInterrupted()
        val observation = GuardedObservation(observations, OBSERVATION, "provider" to endpoint.provider, "operation" to operation)
        var outcome = FAILURE
        var status: Int? = null
        try {
            val reply = observation.inScope { send(request) }
            outcome = SUCCESS
            status = reply.status
            return reply
        } catch (failure: SystemOneFailure) {
            status = failure.status
            throw failure
        } finally {
            val family = SystemOneFailure.family(status)
            observation.stop("outcome" to outcome, "status.family" to family)
            logger.debug("System One operation={} outcome={} status.family={}", operation, outcome, family)
        }
    }

    private fun send(request: () -> RestClient.RequestHeadersSpec<*>): Reply =
        try {
            request().exchangeForRequiredValue({ _, response -> read(response) }, true)
        } catch (failure: SystemOneFailure) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RuntimeException) {
            throw translate(failure)
        }

    private fun read(response: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse): Reply {
        val status = response.statusCode.value()
        val requestId = requestId(response.headers)
        if (status !in 200..299) {
            discard(response)
            throw SystemOneFailure.forStatus(status, requestId)
        }
        val limit = endpoint.maxResponseBytes
        response.body.use { stream ->
            if (response.headers.contentLength > limit) throw SystemOneFailure.invalidResponse(status, requestId)
            val body = stream.readNBytes(limit + 1)
            if (body.size > limit) throw SystemOneFailure.invalidResponse(status, requestId)
            return Reply(body, status, requestId)
        }
    }

    private fun discard(response: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse) {
        try {
            response.body.close()
        } catch (_: IOException) {
            // The status already decides the outcome, and an error body may not exist.
        }
    }

    // Removing the header first keeps an Authorization header the application's client adds away from this endpoint.
    private fun authorize(headers: HttpHeaders, supplier: Supplier<String?>) {
        headers.remove(HttpHeaders.AUTHORIZATION)
        resolveKey(supplier)?.let(headers::setBearerAuth)
    }

    private fun requestId(headers: HttpHeaders): String? =
        endpoint.requestIdHeader?.let(headers::getFirst)?.takeIf(REQUEST_ID::matches)

    private fun translate(failure: RuntimeException): RuntimeException {
        if (failure.isInterruption()) return interrupted()
        val category = when {
            failure.hasTimeout() -> SystemOneFailure.TIMEOUT
            failure is ResourceAccessException -> SystemOneFailure.CONNECTION
            failure is RestClientException -> SystemOneFailure.INVALID_RESPONSE
            else -> SystemOneFailure.OTHER
        }
        val reason = if (category == SystemOneFailure.TIMEOUT || category == SystemOneFailure.CONNECTION) {
            FailureReason.UNAVAILABLE
        } else {
            FailureReason.INVALID_RESPONSE
        }
        return SystemOneFailure(reason, category)
    }

    private fun fallback() = object : SimpleClientHttpRequestFactory() {
        override fun prepareConnection(connection: HttpURLConnection, httpMethod: String) {
            super.prepareConnection(connection, httpMethod)
            connection.instanceFollowRedirects = false
        }
    }.apply {
        setConnectTimeout(endpoint.connectTimeout)
        setReadTimeout(endpoint.readTimeout)
    }

    companion object {
        const val OBSERVATION = "embabel.systemone.request"
        private const val OPERATION_DECIDE = "systemone"
        private const val OPERATION_MODELS = "models"
        private const val SUCCESS = "success"
        private const val FAILURE = "failure"
        private val REQUEST_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        private val logger = LoggerFactory.getLogger(SystemOneTransport::class.java)

        private fun rejectIfInterrupted() {
            if (Thread.currentThread().isInterrupted) throw cancelled()
        }

        private fun interrupted(): CancellationException {
            Thread.currentThread().interrupt()
            return cancelled()
        }

        private fun Throwable.isInterruption(): Boolean =
            Thread.currentThread().isInterrupted || hasCause<InterruptedException>() || hasCause<ClosedByInterruptException>()

        // The failure is not kept as a cause, because transport messages can quote the endpoint.
        private fun cancelled(): CancellationException =
            CancellationException("System One call was cancelled").apply {
                initCause(InterruptedException("System One call was interrupted"))
            }

        private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
            generateSequence(this) { it.cause }.any { it is T }

        private fun Throwable.hasTimeout(): Boolean = generateSequence(this) { it.cause }.any {
            it is SocketTimeoutException || it is HttpTimeoutException || it is TimeoutException
        }
    }
}
