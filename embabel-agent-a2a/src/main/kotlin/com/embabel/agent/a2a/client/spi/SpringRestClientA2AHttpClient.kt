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
package com.embabel.agent.a2a.client.spi

import com.embabel.agent.api.common.Asyncer
import io.a2a.client.http.A2AHttpClient
import io.a2a.client.http.A2AHttpResponse
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * [A2AHttpClient] backed by Spring [RestClient].
 * The OTel Java agent instruments RestClient at runtime, propagating W3C traceparent
 * headers automatically. Construct via [RestClient.builder] to apply customizers
 * (e.g. [org.springframework.http.client.observation.ClientRequestObservationContext]).
 */
class SpringRestClientA2AHttpClient(
    private val restClient: RestClient,
    private val asyncer: Asyncer,
) : A2AHttpClient {

    override fun createGet(): A2AHttpClient.GetBuilder = GetBuilderImpl()
    override fun createPost(): A2AHttpClient.PostBuilder = PostBuilderImpl()
    override fun createDelete(): A2AHttpClient.DeleteBuilder = DeleteBuilderImpl()

    private abstract inner class BaseBuilderImpl<T : A2AHttpClient.Builder<T>> : A2AHttpClient.Builder<T> {
        protected var url: String = ""
        protected val headers = mutableMapOf<String, String>()

        @Suppress("UNCHECKED_CAST")
        override fun url(url: String): T = apply { this.url = url } as T

        @Suppress("UNCHECKED_CAST")
        override fun addHeaders(headers: Map<String, String>): T = apply {
            this.headers.putAll(headers)
        } as T

        @Suppress("UNCHECKED_CAST")
        override fun addHeader(name: String, value: String): T = apply {
            headers[name] = value
        } as T
    }

    private inner class GetBuilderImpl : BaseBuilderImpl<A2AHttpClient.GetBuilder>(), A2AHttpClient.GetBuilder {
        override fun get(): A2AHttpResponse {
            val response = restClient.get().uri(url)
                .headers { h -> headers.forEach { (k, v) -> h.add(k, v) } }
                .retrieve()
                .toEntity(String::class.java)
            return RestClientResponse(response.statusCode.value(), response.statusCode.is2xxSuccessful, response.body ?: "")
        }

        override fun getAsyncSSE(
            onData: Consumer<String>,
            onError: Consumer<Throwable>,
            onComplete: Runnable,
        ): CompletableFuture<Void> = streamSse(url, headers, HttpMethod.GET, null, onData, onError, onComplete)
    }

    private inner class PostBuilderImpl : BaseBuilderImpl<A2AHttpClient.PostBuilder>(), A2AHttpClient.PostBuilder {
        private var body: String? = null

        override fun body(body: String): A2AHttpClient.PostBuilder = apply { this.body = body }

        override fun post(): A2AHttpResponse {
            val response = restClient.post().uri(url)
                .headers { h -> headers.forEach { (k, v) -> h.add(k, v) } }
                .contentType(MediaType.APPLICATION_JSON)
                .body(body ?: "")
                .retrieve()
                .toEntity(String::class.java)
            return RestClientResponse(response.statusCode.value(), response.statusCode.is2xxSuccessful, response.body ?: "")
        }

        override fun postAsyncSSE(
            onData: Consumer<String>,
            onError: Consumer<Throwable>,
            onComplete: Runnable,
        ): CompletableFuture<Void> = streamSse(url, headers, HttpMethod.POST, body, onData, onError, onComplete)
    }

    private inner class DeleteBuilderImpl : BaseBuilderImpl<A2AHttpClient.DeleteBuilder>(), A2AHttpClient.DeleteBuilder {
        override fun delete(): A2AHttpResponse {
            val response = restClient.delete().uri(url)
                .headers { h -> headers.forEach { (k, v) -> h.add(k, v) } }
                .retrieve()
                .toEntity(String::class.java)
            return RestClientResponse(response.statusCode.value(), response.statusCode.is2xxSuccessful, response.body ?: "")
        }
    }

    private fun streamSse(
        url: String,
        headers: Map<String, String>,
        method: HttpMethod,
        body: String?,
        onData: Consumer<String>,
        onError: Consumer<Throwable>,
        onComplete: Runnable,
    ): CompletableFuture<Void> {
        // Run the entire SSE read on the Asyncer's executor so the calling thread is not
        // blocked for the duration of the stream. ExecutorAsyncer also captures the current
        // OTel/MDC context and restores it on the worker, keeping traces and log correlation
        // correct across the thread hop. asyncer.async returns CompletableFuture<Unit>;
        // the cast to Void is safe — type parameters are erased at runtime.
        @Suppress("UNCHECKED_CAST")
        return asyncer.async {
            try {
                // Build the request with caller-supplied headers plus SSE accept type.
                val baseSpec: RestClient.RequestBodySpec = restClient.method(method).uri(url)
                    .headers { h ->
                        headers.forEach { (k, v) -> h.add(k, v) }
                        h.accept = listOf(MediaType.TEXT_EVENT_STREAM)
                    }
                // POST streams carry a JSON body; GET streams do not.
                val exchangeSpec: RestClient.RequestHeadersSpec<*> = if (body != null) {
                    baseSpec.contentType(MediaType.APPLICATION_JSON).body(body)
                } else {
                    baseSpec
                }
                // exchange() bypasses RestClient's default error handlers, so check status
                // explicitly — a 4xx/5xx would otherwise be silently read as an empty stream.
                exchangeSpec.exchange { _, response ->
                    if (response.statusCode.isError) {
                        throw IllegalStateException("A2A stream to $url failed: HTTP ${response.statusCode.value()}")
                    }
                    // Parse SSE: forward only "data:" lines, stripping the prefix.
                    response.body.bufferedReader().forEachLine { line ->
                        if (line.startsWith("data:")) {
                            onData.accept(line.removePrefix("data:").trim())
                        }
                    }
                }
                onComplete.run()
            } catch (e: Throwable) {
                onError.accept(e)
            }
        } as CompletableFuture<Void>
    }
}

private data class RestClientResponse(
    private val status: Int,
    private val success: Boolean,
    private val body: String,
) : A2AHttpResponse {
    override fun status() = status
    override fun success() = success
    override fun body() = body
}
