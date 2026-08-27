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
package com.embabel.agent.test.http

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A local HTTP server that answers with canned chat-completion bodies, optionally after a delay.
 * Shared by the hermetic provider autoconfiguration tests so none of them re-implements server
 * setup and teardown. The bodies themselves are provider-specific and stay in each module.
 *
 * Handlers run on a dedicated executor so [close] can interrupt an in-flight delayed reply.
 * Use with try-with-resources: `try (var server = StubChatServer.replyingWith(BODY)) { ... }`.
 *
 * [replyingInSequence] scripts one [Reply] per request, the last one repeating, so a test can drive
 * a failure-then-recovery exchange and assert on [requestCount].
 */
class StubChatServer private constructor(
    script: List<Reply>,
    delay: Duration,
) : AutoCloseable {

    /** One scripted HTTP reply. */
    data class Reply(val status: Int, val body: String)

    private val requests = AtomicInteger()
    private val executor = Executors.newCachedThreadPool()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                if (!delay.isZero) {
                    Thread.sleep(delay.toMillis())
                }
                val reply = script[minOf(requests.getAndIncrement(), script.size - 1)]
                val bytes = reply.body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                exchange.close()
            } catch (_: IOException) {
                exchange.close()
            }
        }
        server.start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    /** Requests received so far, which is the number of attempts the retry layer actually made. */
    val requestCount: Int get() = requests.get()

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {

        /** Replies immediately with [responseBody]. */
        @JvmStatic
        fun replyingWith(responseBody: String): StubChatServer =
            StubChatServer(listOf(Reply(200, responseBody)), Duration.ZERO)

        /** Replies with [responseBody] only after [delay]. */
        @JvmStatic
        fun replyingAfter(delay: Duration, responseBody: String): StubChatServer =
            StubChatServer(listOf(Reply(200, responseBody)), delay)

        /** Walks [replies] one per request, then repeats the last one. */
        @JvmStatic
        fun replyingInSequence(vararg replies: Reply): StubChatServer =
            StubChatServer(replies.toList(), Duration.ZERO)
    }
}
