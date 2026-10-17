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
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.channels.ClosedByInterruptException
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

/**
 * Sends System One calls over HTTP and hands back the raw body, or throws a [SystemOneFailure].
 *
 * - The key is resolved on every call and set by the last interceptor, so it replaces any
 *   Authorization header the application's client adds. A blank key sends no Authorization
 *   header. A key that can't be resolved or holds control characters fails the call before
 *   anything is sent.
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
        // Run authorization last so application interceptors cannot replace the per-call key.
        .requestInterceptors { it.add(ClientHttpRequestInterceptor(::authorize)) }
        .build()

    /**
     * Sends one encoded question set to the address the endpoint's binding gives for the model.
     *
     * @param model the model the question set is for
     * @param body the encoded request
     * @return the endpoint's reply
     */
    fun decide(model: String, body: ByteArray): Reply = call(OPERATION_DECIDE) {
        restClient.post().uri(endpoint.binding.decisionUri(endpoint, model))
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .attribute(KEY_ATTRIBUTE, resolveKey(apiKey) ?: NO_KEY)
            .body(body)
    }

    /**
     * Lists the endpoint's models.
     *
     * @param key supplies the key for this call, the transport's own supplier by default
     * @return the endpoint's reply
     */
    fun listModels(key: Supplier<String?> = apiKey): Reply = call(OPERATION_MODELS) {
        restClient.get().uri(endpoint.binding.modelsUri(endpoint))
            .accept(MediaType.APPLICATION_JSON)
            .attribute(KEY_ATTRIBUTE, resolveKey(key) ?: NO_KEY)
    }

    /**
     * Reads a key from the supplier. A blank key is null.
     *
     * @param supplier where the key comes from
     * @return the trimmed key, or null when it is missing or blank
     * @throws SystemOneFailure if the supplier fails or the key holds control characters
     * @throws CancellationException if the caller is already interrupted or the supplier was interrupted
     */
    fun resolveKey(supplier: Supplier<String?>): String? {
        rejectIfInterrupted()
        val key = try {
            supplier.get()
        } catch (cancelled: CancellationException) {
            // Preserve the supplier's explicit cancellation, including its identity and cause.
            throw cancelled
        } catch (failure: Exception) {
            // A Kotlin supplier can throw a checked exception, such as InterruptedException, directly.
            if (failure.isInterruption()) throw interrupted()
            throw SystemOneFailure.unavailable(SystemOneFailure.CREDENTIAL)
        }
        if (key.isNullOrBlank()) return null
        if (key.any(Char::isISOControl)) throw SystemOneFailure.unavailable(SystemOneFailure.CREDENTIAL)
        return key.trim()
    }

    /**
     * Runs one observed call. An interrupted caller is cancelled before anything is sent.
     *
     * @param operation the operation tag, such as `systemone` or `models`
     * @param request builds the request to send
     * @return the endpoint's reply
     */
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

    /**
     * Sends the request and turns client exceptions into a [SystemOneFailure] or a cancellation.
     *
     * @param request builds the request to send
     * @return the endpoint's reply
     */
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

    /**
     * Reads a successful response up to the endpoint's limit. An error status fails without
     * reading the body.
     *
     * @param response the raw response
     * @return the body, status and request id
     */
    private fun read(response: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse): Reply {
        val statusCode = response.statusCode
        val status = statusCode.value()
        val requestId = requestId(response.headers)
        if (!statusCode.is2xxSuccessful) {
            discard(response)
            throw SystemOneFailure.forStatus(status, requestId)
        }
        val limit = endpoint.maxResponseBytes
        response.body.use { stream ->
            if (response.headers.contentLength > limit) throw SystemOneFailure.invalidResponse(status, requestId)
            // One lookahead byte distinguishes an exactly full body from an oversized stream.
            val body = stream.readNBytes(limit + 1)
            if (body.size > limit) throw SystemOneFailure.invalidResponse(status, requestId)
            return Reply(body, status, requestId)
        }
    }

    /**
     * Closes an error response's body without reading it.
     *
     * @param response the raw response
     */
    private fun discard(response: RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse) {
        try {
            response.body.close()
        } catch (_: IOException) {
            // The status already decides the outcome, and an error body may not exist.
        }
    }

    /**
     * Sets the key resolved for this call as a bearer token. It runs as the last interceptor, so
     * the key replaces whatever Authorization header the application's client set.
     *
     * @param request the outgoing request
     * @param body the request body
     * @param execution the rest of the chain
     * @return the response
     */
    private fun authorize(request: HttpRequest, body: ByteArray, execution: ClientHttpRequestExecution): ClientHttpResponse {
        request.headers.remove(HttpHeaders.AUTHORIZATION)
        (request.attributes[KEY_ATTRIBUTE] as? String)?.takeIf { it != NO_KEY }?.let(request.headers::setBearerAuth)
        return execution.execute(request, body)
    }

    /**
     * Reads the provider's request id, when the endpoint names a header and the value is well formed.
     *
     * @param headers the response headers
     * @return the request id, or null
     */
    private fun requestId(headers: HttpHeaders): String? =
        endpoint.requestIdHeader?.let(headers::getFirst)?.takeIf(REQUEST_ID::matches)

    /**
     * Maps a client exception to a cancellation or a [SystemOneFailure] with a fixed category.
     *
     * @param failure what the client threw
     * @return the exception to throw in its place
     */
    private fun translate(failure: RuntimeException): RuntimeException {
        if (failure.isInterruption()) return interrupted()
        // Check timeout causes before their client wrappers; the final branch covers other runtime failures.
        val category = when {
            failure.hasTimeout() -> SystemOneFailure.TIMEOUT
            failure is ResourceAccessException -> SystemOneFailure.CONNECTION
            failure is RestClientException -> SystemOneFailure.INVALID_RESPONSE
            else -> SystemOneFailure.OTHER
        }
        val reason = if (category in setOf(SystemOneFailure.TIMEOUT, SystemOneFailure.CONNECTION)) {
            FailureReason.UNAVAILABLE
        } else {
            FailureReason.INVALID_RESPONSE
        }
        return SystemOneFailure(reason, category)
    }

    /**
     * Builds the request factory used when the application supplies no client. It applies the
     * endpoint's timeouts and does not follow redirects.
     *
     * @return the request factory
     */
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
        private val KEY_ATTRIBUTE = SystemOneTransport::class.java.name + ".key"
        private const val NO_KEY = ""
        private const val OPERATION_DECIDE = "systemone"
        private const val OPERATION_MODELS = "models"
        private const val SUCCESS = "success"
        private const val FAILURE = "failure"
        private val REQUEST_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        private val logger = LoggerFactory.getLogger(SystemOneTransport::class.java)

        /**
         * Throws a [CancellationException] when the current thread is interrupted.
         */
        private fun rejectIfInterrupted() {
            if (Thread.currentThread().isInterrupted) throw cancelled()
        }

        /**
         * Sets the interrupt flag again and returns a cancellation to throw.
         *
         * @return the cancellation
         */
        private fun interrupted(): CancellationException {
            Thread.currentThread().interrupt()
            return cancelled()
        }

        /**
         * Tells whether a failure comes from an interrupt.
         *
         * @return true when the thread is interrupted or an interrupt is in the cause chain
         */
        private fun Throwable.isInterruption(): Boolean =
            Thread.currentThread().isInterrupted || hasCause<InterruptedException>() || hasCause<ClosedByInterruptException>()

        /**
         * Creates the cancellation for an interrupted call. The original failure is not kept as
         * its cause, because transport messages can quote the endpoint.
         *
         * @return the cancellation
         */
        private fun cancelled(): CancellationException =
            CancellationException("System One call was cancelled").apply {
                initCause(InterruptedException("System One call was interrupted"))
            }

        /**
         * Tells whether this exception or any of its causes is a [T].
         *
         * @return true when one is
         */
        private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
            hasMatchingCause { it is T }

        /** Walks by identity so a malformed cyclic cause chain cannot stall error handling. */
        private inline fun Throwable.hasMatchingCause(matches: (Throwable) -> Boolean): Boolean {
            val seen = IdentityHashMap<Throwable, Boolean>()
            var current: Throwable? = this
            while (current != null && seen.put(current, true) == null) {
                if (matches(current)) return true
                current = current.cause
            }
            return false
        }

        /**
         * Recognizes socket, JDK HTTP and concurrency timeouts anywhere in the cause chain.
         * Other client failures still receive the connection, invalid-response or other category.
         *
         * @return true when one is
         */
        private fun Throwable.hasTimeout(): Boolean = hasMatchingCause {
            it is SocketTimeoutException || it is HttpTimeoutException || it is TimeoutException
        }
    }
}
