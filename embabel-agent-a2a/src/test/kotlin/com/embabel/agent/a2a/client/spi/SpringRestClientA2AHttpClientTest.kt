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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Unit tests for [SpringRestClientA2AHttpClient] covering all HTTP methods and the SSE streaming path.
 *
 * [MockRestServiceServer] binds to a [RestClient.Builder] and intercepts requests at the
 * [org.springframework.http.client.ClientHttpRequestFactory] level — no WireMock, no prod code change.
 * The built [RestClient] is passed directly to [SpringRestClientA2AHttpClient]'s constructor.
 *
 * The SSE error branch is tested with a separate client backed by a factory that throws [IOException],
 * which is caught by [SpringRestClientA2AHttpClient.streamSse]'s try/catch and forwarded to `onError`.
 */
class SpringRestClientA2AHttpClientTest {

    private lateinit var mockServer: MockRestServiceServer
    private lateinit var client: SpringRestClientA2AHttpClient

    @BeforeEach
    fun setup() {
        val builder = RestClient.builder()
        mockServer = MockRestServiceServer.bindTo(builder).build()
        client = SpringRestClientA2AHttpClient(builder.build())
    }

    @Test
    fun `get returns body and 200 status`() {
        mockServer.expect(requestTo("http://test/resource"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("hello", MediaType.TEXT_PLAIN))

        val r = client.createGet().url("http://test/resource").get()

        assertEquals(200, r.status())
        assertTrue(r.success())
        assertEquals("hello", r.body())
        mockServer.verify()
    }

    @Test
    fun `get forwards custom headers`() {
        mockServer.expect(requestTo("http://test/resource"))
            .andExpect(header("X-Token", "abc"))
            .andRespond(withSuccess("", MediaType.TEXT_PLAIN))

        client.createGet()
            .url("http://test/resource")
            .addHeaders(mapOf("X-Token" to "abc"))
            .get()

        mockServer.verify()
    }

    @Test
    fun `post sends body and returns 200`() {
        mockServer.expect(requestTo("http://test/rpc"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string("{\"key\":1}"))
            .andRespond(withSuccess("{\"result\":true}", MediaType.APPLICATION_JSON))

        val r = client.createPost()
            .url("http://test/rpc")
            .body("{\"key\":1}")
            .post()

        assertEquals(200, r.status())
        assertTrue(r.success())
        mockServer.verify()
    }

    @Test
    fun `delete returns 204`() {
        mockServer.expect(requestTo("http://test/resource"))
            .andExpect(method(HttpMethod.DELETE))
            .andRespond(withStatus(HttpStatus.NO_CONTENT))

        val r = client.createDelete().url("http://test/resource").delete()

        assertEquals(204, r.status())
        assertTrue(r.success())
        mockServer.verify()
    }

    @Test
    fun `getAsyncSSE delivers parsed data lines and calls onComplete`() {
        mockServer.expect(requestTo("http://test/sse"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("data: line1\n\ndata: line2\n\n", MediaType.TEXT_EVENT_STREAM))

        val received = CopyOnWriteArrayList<String>()
        val completed = AtomicBoolean(false)

        client.createGet()
            .url("http://test/sse")
            .getAsyncSSE(
                { received.add(it) },
                { throw it },
                { completed.set(true) },
            ).get(5, TimeUnit.SECONDS)

        assertEquals(listOf("line1", "line2"), received.toList())
        assertTrue(completed.get())
        mockServer.verify()
    }

    @Test
    fun `streamSse calls onError when request factory throws`() {
        val errorClient = SpringRestClientA2AHttpClient(
            RestClient.builder()
                .requestFactory { _, _ -> throw IOException("connection refused") }
                .build()
        )
        val error = AtomicReference<Throwable>()

        errorClient.createGet()
            .url("http://unreachable/sse")
            .getAsyncSSE(
                { },
                { t -> error.set(t) },
                { },
            ).get(5, TimeUnit.SECONDS)

        assertNotNull(error.get())
        assertInstanceOf(IOException::class.java, error.get().cause)
    }
}
